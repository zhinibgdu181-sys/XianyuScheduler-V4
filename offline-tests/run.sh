#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_java_bin="${TEST_JAVA_BIN:-java}"
test_classes_dir="$(mktemp -d)"
trap 'rm -rf "$test_classes_dir"' EXIT
src="$project_root/app/src/main/java/com/zhinibgdu/xianyu"
tests="$project_root/offline-tests"
"$test_java_bin" "$tests/ParseAll.java" "$project_root/app/src/main/java"
mapfile -t vision_sources < <(find "$tests/vision" -name '*.java' -print)
"$test_java_bin" com.sun.tools.javac.Main -d "$test_classes_dir/vision" "${vision_sources[@]}" \
 "$src/FruitGameSolver.java" "$src/MahjongGameSolver.java" "$src/RootCommandRunner.java" \
 "$src/PairVerification.java" "$src/GameTapPolicy.java"
"$test_java_bin" -cp "$test_classes_dir/vision" com.zhinibgdu.xianyu.RuntimeRegression
"$test_java_bin" -cp "$test_classes_dir/vision" com.zhinibgdu.xianyu.Replay "$tests/11873.jpg"
mapfile -t ocr_sources < <(find "$tests/ocr" -name '*.java' -print)
"$test_java_bin" com.sun.tools.javac.Main -d "$test_classes_dir/ocr" "${ocr_sources[@]}" \
 "$tests/vision/android/graphics/Bitmap.java" "$tests/vision/android/content/Context.java" \
 "$src/ScreenOcr.java" "$src/RootCommandRunner.java"
printf '#!/bin/sh\nexit 0\n' > "$test_classes_dir/fake-su"
chmod +x "$test_classes_dir/fake-su"
"$test_java_bin" -cp "$test_classes_dir/ocr" com.zhinibgdu.xianyu.OcrRegression "$test_classes_dir/fake-su"
