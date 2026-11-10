$ErrorActionPreference = "Stop"

$repoRoot = $PSScriptRoot
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS

Push-Location $repoRoot
try {
	.\gradlew.bat --stop
	$env:JAVA_TOOL_OPTIONS = '-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC'
	.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
} finally {
	if ($null -eq $previousJavaToolOptions) {
		Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
	} else {
		$env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
	}
	Pop-Location
}
