/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gbif.dp.common.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Outcome of {@link DataPackageSource#openResource(String)}.
 *
 * <p>Modelled as three explicit outcomes rather than a nullable {@link InputStream} plus a
 * thrown exception, so that "resource not declared" (a data package correctness issue) and
 * "resource declared but unreadable" (an infrastructure/permissions issue) are distinguishable
 * by callers without both being folded into the same exception type.
 *
 * <p>Not a {@code sealed} interface: exhaustiveness-checked {@code switch} over sealed
 * permitted types requires Java 21 (JEP 441); on the Java 17 this project targets, the
 * pattern-matching {@code switch} needed for that guarantee is preview-only. {@link #kind()}
 * is the interim substitute — callers dispatching on it via {@code instanceof} should throw
 * explicitly on an unhandled kind rather than silently falling through.
 * TODO: revisit on JDK 21 — make this {@code sealed} and switch call sites to
 * pattern-matching {@code switch}.
 */
public interface ResourceResult {

  Kind kind();

  enum Kind {
    FOUND,
    MISSING,
    FAILED
  }

  /**
   * The resource was found and opened.
   *
   * <p>Implements {@link Closeable} itself (delegating to {@link #stream()}) so callers can
   * try-with-resources directly on the result — {@code try (var found = (Found) result) { ... }}
   * — rather than reaching into {@code found.stream()} for a nested try-with-resources. This
   * is a resource-read-scoped lifetime, independent of the {@link DataPackageSource}'s own
   * {@link DataPackageSource#close()}, which governs the longer-lived session/connection
   * a source may hold across many {@code openResource} calls.
   */
  record Found(InputStream stream) implements ResourceResult, Closeable {
    @Override
    public Kind kind() {
      return Kind.FOUND;
    }

    @Override
    public void close() throws IOException {
      stream.close();
    }
  }

  /** No resource exists at {@code relativePath}. */
  record Missing(String relativePath) implements ResourceResult {
    @Override
    public Kind kind() {
      return Kind.MISSING;
    }
  }

  /** A resource exists at {@code relativePath} but could not be opened. */
  record Failed(String relativePath, IOException cause) implements ResourceResult {
    @Override
    public Kind kind() {
      return Kind.FAILED;
    }
  }
}
