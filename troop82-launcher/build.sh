#!/bin/sh
# Build, sign and (with "install") push the Troop 82 launcher. No Gradle needed.
set -e
cd "$(dirname "$0")"
T=/c/tcl/tools
export JAVA_HOME="$T/jdk-17.0.21.1+2"; export PATH="$JAVA_HOME/bin:$PATH"
BT="$T/sdk/build-tools/34.0.0"; JAR="$T/sdk/platforms/android-34/android.jar"
VERSION_CODE=${VERSION_CODE:-$(date +%y%m%d%H)}
rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk \
  --java build/gen --min-sdk-version 31 --target-sdk-version 31 \
  --version-code "$VERSION_CODE" --version-name "1.0.$VERSION_CODE" build/res.zip
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" \
  -d build/classes $(find src build/gen -name '*.java')
"$BT/d8.bat" --release --min-api 31 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
python - <<'PY'
import zipfile
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f troop82.keystore ]; then
  if [ "$NEW_KEY" != "1" ]; then
    echo "ERROR: troop82.keystore is missing. Restore it from C:/tcl/backup/troop82.keystore." >&2
    echo "(Only for a brand-new install: NEW_KEY=1 ./build.sh)" >&2
    exit 1
  fi
  keytool -genkeypair -keystore troop82.keystore -storepass troop82 -keypass troop82 \
  -alias troop82 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Troop 82 Launcher"
fi
"$BT/apksigner.bat" sign --ks troop82.keystore --ks-pass pass:troop82 --out troop82-launcher.apk build/aligned.apk
echo "Built $(pwd)/troop82-launcher.apk (version $VERSION_CODE)"

if [ "$1" = "install" ]; then
  adb -s "${TV:-192.168.1.174:5555}" install -r troop82-launcher.apk
fi
