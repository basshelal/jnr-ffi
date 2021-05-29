/*
 * Copyright (C) 2012 Wayne Meissner
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

package jnr.ffi.provider;

import jnr.ffi.NativeType;
import jnr.ffi.ObjectReferenceManager;
import jnr.ffi.Runtime;
import jnr.ffi.Type;
import jnr.ffi.TypeAlias;

import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * An invalid {@link jnr.ffi.Runtime} subclass that throws exceptions for all methods
 *
 * @see jnr.ffi.provider.jffi.NativeRuntime
 */
class InvalidRuntime extends Runtime {
    private final String message;
    private final Throwable cause;

    InvalidRuntime(String message, Throwable cause) {
        this.message = message;
        this.cause = cause;
    }

    @Override
    public Type findType(NativeType type) {
        throw newLoadError();
    }

    @Override
    public Type findType(TypeAlias type) {
        throw newLoadError();
    }

    @Override
    public MemoryManager getMemoryManager() {
        throw newLoadError();
    }

    @Override
    public ClosureManager getClosureManager() {
        throw newLoadError();
    }

    @Override
    public ObjectReferenceManager newObjectReferenceManager() {
        throw newLoadError();
    }

    @Override
    public int getLastError() {
        throw newLoadError();
    }

    @Override
    public void setLastError(int error) {
        throw newLoadError();
    }

    @Override
    public long addressMask() {
        throw newLoadError();
    }

    @Override
    public int addressSize() {
        throw newLoadError();
    }

    @Override
    public int longSize() {
        throw newLoadError();
    }

    @Override
    public ByteOrder byteOrder() {
        throw newLoadError();
    }

    @Override
    public boolean isCompatible(Runtime other) {
        throw newLoadError();
    }

    @Override
    public Map<List<String>, List<String>> getLoadedLibraryPaths() {
        throw newLoadError();
    }

    private UnsatisfiedLinkError newLoadError() {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
        error.initCause(cause);
        throw error;
    }
}
