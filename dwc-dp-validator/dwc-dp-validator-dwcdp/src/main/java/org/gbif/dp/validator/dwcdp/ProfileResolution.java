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
package org.gbif.dp.validator.dwcdp;

/** Outcome of {@link DwcDpProfileRegistry#resolveDetailed(String)}. */
public sealed interface ProfileResolution {

  record Resolved(DwcDpSchemaVersion version) implements ProfileResolution {}

  /**
   * @param detail short, human-readable explanation (no trailing period) suitable for
   *               embedding in a validation issue message
   */
  record Unresolved(Reason reason, String detail) implements ProfileResolution {}

  enum Reason {
    /** The descriptor has no {@code profile}. */
    MISSING,
    /** Not bundled, and not an absolute http(s) URL so it can't be fetched either. */
    NOT_A_URL,
    /** Not bundled, and remote fetching is switched off ({@link SchemaFetchPolicy#none()}). */
    REMOTE_DISABLED,
    /** Not bundled, and the {@link SchemaFetchPolicy} does not allow fetching it. */
    NOT_ALLOWED,
    /** Allowed, but the HTTP fetch failed (network error, non-200, too large, ...). */
    FETCH_FAILED,
    /** Fetched, but the document is not a JSON Schema / DwC-DP profile. */
    NOT_A_PROFILE,
    /** Fetched, but building the JSON Schema failed (e.g. a disallowed or broken {@code $ref}). */
    LOAD_FAILED
  }
}
