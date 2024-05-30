#!/usr/bin/env bash

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

# This script runs something like:

# java -jar lucene/target/cuvs-searcher-lucene-0.0.1-SNAPSHOT-jar-with-dependencies.jar \
#   <datasetfile> \
#   <vector_index_column> \
#   <name_of_vector_field> \
#   <numDocs> \
#   <dimensions> \
#   <queryFile>

lucene/LuceneVectorSearchExample \
    "$(rlocation dataset/file/dataset.zip)" \
    5 \
    content_vector \
    25000 \
    768 \
    "$(rlocation lucene-cuvs/lucene/query.txt)"
