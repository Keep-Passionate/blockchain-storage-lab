# 文件功能：在 Windows 上直接编译并运行 Java 最小闭环演示。
param(
    [string]$Root = "$PSScriptRoot\..\runs\demo"
)
$ErrorActionPreference = "Stop"
$javaRoot = Join-Path $PSScriptRoot "..\core-java\src\main\java"
$outDir = Join-Path $PSScriptRoot "..\core-java\out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$files = Get-ChildItem -Recurse -Filter *.java $javaRoot | ForEach-Object FullName
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { "java" }
$javac = if (Test-Path (Join-Path $jdk "javac.exe")) { Join-Path $jdk "javac.exe" } else { "javac" }
$java = if (Test-Path (Join-Path $jdk "java.exe")) { Join-Path $jdk "java.exe" } else { "java" }
& $javac -encoding UTF-8 -d $outDir $files
& $java -cp $outDir lab.blockchain.core.DemoMain $Root
