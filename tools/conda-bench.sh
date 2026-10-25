#!/usr/bin/env bash
# Run the emulator bench with its own Miniconda environment.
set -euo pipefail
tools_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [[ "$(uname -s)" == Darwin ]]; then
  default_miniconda="$HOME/Library/Developer/Syclop/miniconda3"
else
  default_miniconda="$HOME/miniconda3"
fi
miniconda_root="${SYCLOP_MINICONDA_HOME:-$default_miniconda}"
bench_prefix="${SYCLOP_CONDA_PREFIX:-$miniconda_root/envs/syclop-bench}"
if [[ ! -x "$miniconda_root/bin/conda" || ! -x "$bench_prefix/bin/python" ]]; then
  printf 'Create the syclop-bench environment first; see tools/ANDROID_BENCH.md.\nSet SYCLOP_MINICONDA_HOME or SYCLOP_CONDA_PREFIX for a different installation.\n' >&2
  exit 1
fi
exec "$miniconda_root/bin/conda" run --prefix "$bench_prefix" --no-capture-output \
  python "$tools_dir/android-bench.py" "$@"
