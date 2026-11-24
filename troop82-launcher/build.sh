#!/bin/sh
# Build and sign the Troop 82 launcher. No Gradle needed.
#   ./build.sh              build only
#   ./build.sh release      build, run tests, and mark for automatic rollout by self-heal
#   ./build.sh install [ip] build and install now on registered TVs (or the given one)
set -e
cd "$(dirname "$0")"
case "${1:-}" in ""|release|install) ;; *) echo "usage: ./build.sh [release | install [tv-ip ...]]" >&2; exit 1;; esac
T=/c/tcl/tools
export JAVA_HOME="$T/jdk-17.0.21.1+2"; export PATH="$JAVA_HOME/bin:$PATH"
BT="$T/sdk/build-tools/34.0.0"; JAR="$T/sdk/platforms/android-34/android.jar"
# seconds since 1970: always increases (fits Android's int until 2038), so every build installs as an upgrade
VERSION_CODE=${VERSION_CODE:-$(date +%s)}
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

# ./build.sh release  -> run the tests; if they pass, mark this build for automatic rollout.
# Self-heal only rolls out builds marked here, so an experimental build never reaches the TVs.
if [ "${1:-}" = "release" ]; then
  rm -rf build/test && mkdir -p build/test
  javac -encoding UTF-8 -nowarn -d build/test -cp "$JAR" src/org/troop82/launcher/Announcements.java test/ParserTest.java
  if java -cp "$(cygpath -w build/test);$(cygpath -w "$JAR")" org.troop82.launcher.ParserTest | tail -1 | grep -q "ALL PARSER TESTS PASSED"; then
    # the tested APK and its version are copied together; self-heal installs only this pair
    mkdir -p release
    cp troop82-launcher.apk release/troop82-launcher.apk
    echo "$VERSION_CODE" > release/troop82-launcher.version
    echo "Tests passed: version $VERSION_CODE released (self-heal will install it on registered TVs)"
  else
    echo "Tests FAILED: not released" >&2; exit 1
  fi
fi

# ./build.sh install            -> every TV in ../tv-addresses.txt
# ./build.sh install 192.168.1.158  -> just that TV
if [ "${1:-}" = "install" ]; then
  shift
  targets="$*"
  [ -z "$targets" ] && targets=$(tr -d '\r' < ../tv-addresses.txt 2>/dev/null)
  for t in $targets; do
    case "$t" in *:*) ;; *) t="$t:5555";; esac
    if tr -d '\r \t' < ../heal-skip.txt 2>/dev/null | sed 's/:5555$//' | grep -qxF "${t%:5555}"; then echo "$t: skipped (in heal-skip.txt)"; continue; fi
    adb connect "$t" >/dev/null 2>&1
    s=$(adb -s "$t" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
    if ! tr -d '\r' < ../tv-serials.txt 2>/dev/null | grep -qxF "$s"; then echo "$t: not a registered Troop 82 TV (run setup-tv.sh) - skipped"; continue; fi
    if tr -d '\r \t' < ../heal-skip.txt 2>/dev/null | grep -qxF "$s"; then echo "$t: skipped (serial in heal-skip.txt)"; continue; fi
    echo "$t: $(adb -s "$t" install -r troop82-launcher.apk 2>&1 | tail -1)"
  done
fi
