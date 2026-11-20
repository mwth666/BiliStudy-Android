#!/usr/bin/env bash
# B站学习陪伴 Android 版 —— 离线构建（不需要 Android Studio / Gradle / 联网）
set -e
export PATH="/e/Android/jbr/bin:$PATH"
BT="/c/Users/68335/AppData/Local/Android/Sdk/build-tools/35.0.1"
PLATFORM="/c/Users/68335/AppData/Local/Android/Sdk/platforms/android-35/android.jar"
cd "$(dirname "$0")"

rm -rf build/compiled build/dex build/classes build/gen
mkdir -p build/compiled build/dex build/classes build/gen

echo "[1/5] 编译资源..."
"$BT/aapt2.exe" compile --dir app/src/main/res -o build/compiled/res.zip

echo "[2/5] 链接资源 + 生成 R.java..."
"$BT/aapt2.exe" link -o build/base.apk -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml --java build/gen \
  --min-sdk-version 23 --target-sdk-version 34 --version-code 1 --version-name 1.0 \
  build/compiled/res.zip

echo "[3/5] 编译 Java..."
javac -encoding UTF-8 --release 8 -nowarn \
  -classpath "$PLATFORM" -d build/classes \
  $(find app/src/main/java -name "*.java") $(find build/gen -name "*.java")

echo "[4/5] 转 dex..."
"$BT/d8.bat" --min-api 23 --lib "$PLATFORM" --output build/dex $(find build/classes -name "*.class")

echo "[5/5] 打包 / 对齐 / 签名..."
python - <<'PY'
import zipfile, shutil
shutil.copy2('build/base.apk', 'build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --ks-key-alias bilistudy \
  --min-sdk-version 23 --out BiliStudy.apk build/aligned.apk

echo "完成: $(ls -la BiliStudy.apk | awk '{print $5}') bytes"
