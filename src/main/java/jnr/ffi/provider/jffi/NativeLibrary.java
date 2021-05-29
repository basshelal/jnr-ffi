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
import jnr.ffi.LibraryLoader;
import jnr.ffi.Platform;
import jnr.ffi.Runtime;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a native library made up of potentially multiple native library files to create a "composite library".
 * This represents a single Java mapped interface which could be made up of multiple native library files.
 *
 * This is basically a wrapper around {@link com.kenai.jffi.Library} with added functionality for multiple library
 * file support
 *
 * <strong>You should not be using this class directly</strong>
 */
class NativeLibrary {
    private final List<String> libraryNames;
    private final List<String> searchPaths;
    private final List<String> successfulPaths = new ArrayList<>();

    private volatile List<com.kenai.jffi.Library> nativeLibraries = Collections.emptyList();

    NativeLibrary(Collection<String> libraryNames, Collection<String> searchPaths, boolean loadNow) {
        this.libraryNames = Collections.unmodifiableList(new ArrayList<>(libraryNames));
        this.searchPaths = Collections.unmodifiableList(new ArrayList<>(searchPaths));
        if (loadNow) getNativeLibraries();
    }

    private String locateLibrary(String libraryName) {
        return Platform.getNativePlatform().locateLibrary(libraryName, searchPaths);
    }

    /**
     * Gets the address of a symbol with the given name, 0 means the address was not found
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
        List<com.kenai.jffi.Library> libs = new ArrayList<com.kenai.jffi.Library>();

        for (String libraryName : libraryNames) {
            if (libraryName.equals(LibraryLoader.DEFAULT_LIBRARY)) {
                libs.add(Library.getDefault());
                continue;
            }

            com.kenai.jffi.Library lib;

            // try opening ignoring searchPaths AND any name mapping, so just literal given name
            lib = openLibrary(libraryName, successfulPaths);
            if (lib == null) {
                String path;
                if (libraryName != null && (path = locateLibrary(libraryName)) != null && !libraryName.equals(path)) {
                    // try opening after using locateLibrary(), will map and use searchPaths
                    lib = openLibrary(path, successfulPaths);
                }
            }
            if (lib == null) {
                throw new UnsatisfiedLinkError(com.kenai.jffi.Library.getLastError() +
                        "\nSearch paths:\n" + searchPaths.toString());
            }
            libs.add(lib);
        }
        if (Runtime.getSystemRuntime() instanceof NativeRuntime) {
            ((NativeRuntime) Runtime.getSystemRuntime())
                    .addSuccessfulLibraryPaths(this.libraryNames, this.successfulPaths);
        }

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
}
