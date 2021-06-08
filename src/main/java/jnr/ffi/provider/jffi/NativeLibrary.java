/*
 * Copyright (C) 2008-2010 Wayne Meissner
 *
 * This file is part of the JNR project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jnr.ffi.provider.jffi;

import com.kenai.jffi.Library;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jnr.ffi.LibraryLoader;
import jnr.ffi.LibraryOption;
import jnr.ffi.Platform;
import jnr.ffi.Runtime;

public class NativeLibrary {
/**
 * Represents a native library made up of potentially multiple native library files to create a "composite library".
 * This represents a single Java mapped interface which could be made up of multiple native library files.
 *
 * This is basically a wrapper around {@link com.kenai.jffi.Library} with added functionality for multiple library
 * file support
 *
 * An interface mapping of a library loaded with {@link AsmLibraryLoader} will always implement
 * {@link AbstractAsmLibraryInterface} which contains a strong reference to the {@link NativeLibrary} representing
 * the loaded library, see {@link AbstractAsmLibraryInterface#getLibrary()}
 *
 * An interface mapping of a library loaded with {@link ReflectionLibraryLoader} will only implement
 * {@link jnr.ffi.provider.LoadedLibrary} but will also contain (deep in the object chain) a strong reference to the
 * {@link NativeLibrary} representing the loaded library.
 *
 * If an instance of this class has been GC'd, it means that all instances of an interface mapping have been GC'd and
 * the library can be (and will be) safely unloaded. This is reflected in {@link Runtime#getLoadedLibraries()} which
 * will show all currently loaded libraries.
 *
 * @see LoadedLibraryData
 */
class NativeLibrary {
    private final List<String> libraryNames;
    private final List<String> searchPaths;
    private final List<String> successfulPaths = new ArrayList<>();
    private final Map<LibraryOption, Object> options;

    private volatile List<com.kenai.jffi.Library> nativeLibraries = Collections.emptyList();

    NativeLibrary(Collection<String> libraryNames, Collection<String> searchPaths,
                  Map<LibraryOption, Object> options) {
        this.libraryNames = Collections.unmodifiableList(new ArrayList<>(libraryNames));
        this.searchPaths = Collections.unmodifiableList(new ArrayList<>(searchPaths));
        this.options = options;
        if (options.containsKey(LibraryOption.LoadNow)) getNativeLibraries();
    }

    private String locateLibrary(String libraryName) {
        return Platform.getNativePlatform().locateLibrary(libraryName, searchPaths, options);
    }

    /**
     * Gets the first address of a symbol with the given name, 0 means the address was not found
     */
    long getSymbolAddress(String name) {
        for (com.kenai.jffi.Library l : getNativeLibraries()) {
            long address = l.getSymbolAddress(name);
            if (address != 0) {
                return address;
            }
        }
        return 0;
    }

    /**
     * Same as {@link #getSymbolAddress(String)} except throws a {@link SymbolNotFoundError} if the symbol was not found
     */
    long findSymbolAddress(String name) {
        long address = getSymbolAddress(name);
        if (address == 0) {
            throw new SymbolNotFoundError(com.kenai.jffi.Library.getLastError());
        }
        return address;
    }

    private synchronized List<com.kenai.jffi.Library> getNativeLibraries() {
        if (!this.nativeLibraries.isEmpty()) {
            return nativeLibraries;
        }
        return nativeLibraries = loadNativeLibraries();
    }

    /**
     * Loads all entries from {@link #libraryNames} into {@link Library}s and returns that list
     * Theoretically, this should only ever be called once in a library's lifetime, upon first call of {@link #getNativeLibraries()}
     */
    private synchronized List<com.kenai.jffi.Library> loadNativeLibraries() {
        List<com.kenai.jffi.Library> libs = new ArrayList<>();

        for (String libraryName : libraryNames) {
            if (libraryName == null) continue;
            if (libraryName.equals(LibraryLoader.DEFAULT_LIBRARY)) {
                libs.add(Library.getDefault());
                continue;
            }

            com.kenai.jffi.Library lib;

            // try opening ignoring searchPaths AND any name mapping, so just literal given name
            lib = openLibrary(libraryName, successfulPaths);
            if (lib == null) {
                String path = locateLibrary(libraryName); // try opening with mapping and searchPaths
                if (!libraryName.equals(path)) {
                    lib = openLibrary(path, successfulPaths);
                }
            }
            if (lib == null) {
                throw new UnsatisfiedLinkError(com.kenai.jffi.Library.getLastError() +
                        "\nLibrary names\n" + libraryNames.toString() +
                        "\nSearch paths:\n" + searchPaths.toString());
            }
            libs.add(lib);
        }
        putLibraryIntoRuntime(); // successfulPaths have been set and library has been loaded

        return Collections.unmodifiableList(libs);
    }

    private static final Pattern BAD_ELF = Pattern.compile("(.*): (invalid ELF header|file too short|invalid file format)");
    private static final Pattern ELF_GROUP = Pattern.compile("GROUP\\s*\\(\\s*(\\S*).*\\)");

    /**
     * Tries to open the library with the given path
     *
     * @param path            the absolute path or name of the library to open
     * @param successfulPaths will be populated with paths of libraries that loaded successfully
     * @return the {@link Library} if successfully found, or {@code null} otherwise
     */
    private static com.kenai.jffi.Library openLibrary(String path, List<String> successfulPaths) {
        com.kenai.jffi.Library lib;

        lib = com.kenai.jffi.Library.getCachedInstance(path, com.kenai.jffi.Library.LAZY | com.kenai.jffi.Library.GLOBAL);
        if (lib != null) {
            successfulPaths.add(path);
            return lib;
        }

        // If dlopen() fails with 'invalid ELF header', then it is likely to be a ld script - parse it for the real library path
        Matcher badElf = BAD_ELF.matcher(com.kenai.jffi.Library.getLastError());
        if (badElf.lookingAt()) {
            File f = new File(badElf.group(1));
            if (f.isFile() && f.length() < (4 * 1024)) {
                Matcher sharedObject = ELF_GROUP.matcher(readAll(f));
                if (sharedObject.find()) {
                    lib = com.kenai.jffi.Library.getCachedInstance(sharedObject.group(1), com.kenai.jffi.Library.LAZY | com.kenai.jffi.Library.GLOBAL);
                    if (lib != null) successfulPaths.add(path);
                    return lib;
                }
            }
        }

        return null;
    }

    private static String readAll(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);

        } catch (IOException ioe) {
            throw new RuntimeException(ioe);

        } finally {
            if (br != null) try { br.close(); } catch (IOException e) { throw new RuntimeException(e); }
        }
    }

    private void putLibraryIntoRuntime() {
        if (Runtime.getSystemRuntime() instanceof NativeRuntime) {
            ((NativeRuntime) Runtime.getSystemRuntime())
                    .loadedLibraries.put(this, new LoadedLibraryData(libraryNames, searchPaths, successfulPaths));
        }
    }

    /**
     * Data class containing information about a loaded native library.
     *
     * A list of all currently loaded libraries can be queried using {@link Runtime#getLoadedLibraries()} which will
     * return a list of {@link LoadedLibraryData}s.
     */
    public static class LoadedLibraryData {

        private final List<String> libraryNames;
        private final List<String> searchPaths;
        private final List<String> successfulPaths;

        LoadedLibraryData(List<String> libraryNames, List<String> searchPaths, List<String> successfulPaths) {
            this.libraryNames = Collections.unmodifiableList(libraryNames);
            this.searchPaths = Collections.unmodifiableList(searchPaths);
            this.successfulPaths = Collections.unmodifiableList(successfulPaths);
        }

        /**
         * @return the list of library names that were provided when this library was loaded
         */
        public List<String> getLibraryNames() {
            return libraryNames;
        }

        /**
         * @return the list of paths that were used to search for the library, custom paths will always appear before
         *         any system default paths
         */
        public List<String> getSearchPaths() {
            return searchPaths;
        }

        /**
         * @return the list of absolute paths of the loaded library files (.so, .dylib, .dll etc) that were actually
         *         loaded, these are the native library files that are being used for this library
         */
        public List<String> getSuccessfulPaths() {
            return successfulPaths;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LoadedLibraryData)) return false;
            LoadedLibraryData that = (LoadedLibraryData) o;
            return Objects.equals(libraryNames, that.libraryNames) &&
                    Objects.equals(searchPaths, that.searchPaths) &&
                    Objects.equals(successfulPaths, that.successfulPaths);
        }

        @Override
        public int hashCode() {
            return Objects.hash(libraryNames, searchPaths, successfulPaths);
        }

        @Override
        public String toString() {
            return "LoadedLibraryData {" +
                    "libraryNames=" + libraryNames +
                    ", searchPaths=" + searchPaths +
                    ", successfulPaths=" + successfulPaths +
                    '}';
        }
    }
}
