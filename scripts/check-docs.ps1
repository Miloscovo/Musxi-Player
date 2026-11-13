$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$files = @((Get-ChildItem -LiteralPath $root -File -Filter '*.md')) +
    @((Get-ChildItem -LiteralPath (Join-Path $root 'docs') -Recurse -File -Filter '*.md'))
$broken = [Collections.Generic.List[string]]::new()
foreach ($file in $files) {
    $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    foreach ($match in [regex]::Matches($content, '\[[^\]\r\n]*\]\((?<target><[^>]+>|[^\s)]+)(?:\s+"[^"]*")?\)')) {
        $target = $match.Groups['target'].Value.Trim('<', '>')
        if ($target -match '^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|#|//)' -or $target -match '(^|/)\.local/') { continue }
        $path = [Uri]::UnescapeDataString(($target -split '[#?]', 2)[0])
        if (!$path) { continue }
        $base = if ($path.StartsWith('/')) { $root } else { $file.DirectoryName }
        $resolved = [IO.Path]::GetFullPath((Join-Path $base $path.TrimStart('/')))
        if (!(Test-Path -LiteralPath $resolved)) {
            $relative = [IO.Path]::GetRelativePath($root, $file.FullName)
            $broken.Add("${relative}: $target")
        }
    }
}
if ($broken.Count) { throw "Broken local documentation links:`n$($broken -join "`n")" }
Write-Host "Documentation links: $($files.Count) Markdown files checked. Local evidence paths are excluded."
