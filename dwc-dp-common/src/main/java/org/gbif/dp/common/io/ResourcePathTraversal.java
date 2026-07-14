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

/**
 * Rejects directory traversal in a resource path declared in a Data Package descriptor
 * (Frictionless {@code resources[].path}), independent of what the path is eventually
 * resolved against — a local base directory, a base URI, or any other backend.
 *
 * <p>Checked against the raw, undecoded path string before any backend-specific resolution:
 * filesystem {@code Path} normalization and {@link java.net.URI#resolve} both collapse
 * {@code ..} segments, but they don't collapse them identically, and a URI resolver in
 * particular will walk past its base entirely given enough of them. Rejecting {@code ..}
 * up front makes the policy backend-independent rather than relying on each resolution
 * strategy to happen to stop at the right boundary.
 */
public final class ResourcePathTraversal {

  private ResourcePathTraversal() {}

  /** @return true if any path segment (split on {@code /} or {@code \}) is exactly {@code ..} */
  public static boolean containsTraversal(String rawPath) {
    for (String segment : rawPath.split("[/\\\\]")) {
      if (segment.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
