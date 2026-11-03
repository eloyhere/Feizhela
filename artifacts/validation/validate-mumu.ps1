$ErrorActionPreference = 'Stop'
$adb = 'R:\Android\Sdk\platform-tools\adb.exe'
$serial = '127.0.0.1:16384'
function Invoke-Adb {
    & $adb -s $serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
}
function Test-Ui($name) {
    $result = Invoke-Adb shell am instrument -w -r com.feizhela.headband.test/androidx.test.runner.AndroidJUnitRunner
    $result | Set-Content -Encoding utf8 "artifacts/validation/$name-tests.txt"
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(4 tests\)') { throw "UI tests failed: $name" }
}
function Capture-Ui($name) {
    Invoke-Adb shell am start -W -n com.feizhela.headband/.MainActivity
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell screencap -p /sdcard/feizhela-screen.png
    Invoke-Adb pull /sdcard/feizhela-screen.png "artifacts/validation/$name.png"
}
Invoke-Adb install -r app/build/outputs/apk/debug/app-debug.apk
Invoke-Adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
try {
    Test-Ui 'narrow-light'
    Capture-Ui 'narrow-light'
    Invoke-Adb shell wm density 240
    Invoke-Adb shell cmd uimode night yes
    Test-Ui 'wide-dark'
    Capture-Ui 'wide-dark'
}
finally {
    Invoke-Adb shell wm density reset
    Invoke-Adb shell cmd uimode night no
    Invoke-Adb shell am start -W -n com.feizhela.headband/.MainActivity
}
