param(
    [Parameter(Mandatory=$true)][string]$FeatureSource,
    [string]$Destination = 'docs/play-store/zh-TW'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$destinationPath = (Resolve-Path -LiteralPath $Destination).Path

# Normalize the generated feature art to Play's exact upload dimensions.
$source = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $FeatureSource).Path)
try {
    $feature = New-Object System.Drawing.Bitmap 1024,500
    $graphics = [System.Drawing.Graphics]::FromImage($feature)
    try {
        $graphics.Clear([System.Drawing.Color]::White)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($source, 0, 0, 1024, 500)
        $feature.Save((Join-Path $destinationPath 'feature-graphic-1024x500.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $feature.Dispose() }
} finally { $source.Dispose() }

# Export the existing Android vector identity without changing the app resources.
[xml]$vector = Get-Content -Encoding UTF8 'app/src/main/res/drawable/ic_launcher_foreground.xml'
[xml]$colors = Get-Content -Encoding UTF8 'app/src/main/res/values/colors.xml'
$androidNs = 'http://schemas.android.com/apk/res/android'
$icon = New-Object System.Drawing.Bitmap 512,512
$graphics = [System.Drawing.Graphics]::FromImage($icon)
try {
    $background = ($colors.resources.color | Where-Object { $_.name -eq 'ic_launcher_background' }).InnerText
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml($background))
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $scale = 512.0 / [float]$vector.vector.GetAttribute('viewportWidth', $androidNs)
    $graphics.ScaleTransform($scale, $scale)
    $group = $vector.vector.group
    $graphics.TranslateTransform([float]$group.GetAttribute('translateX', $androidNs), [float]$group.GetAttribute('translateY', $androidNs))
    foreach ($node in $group.path) {
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($node.GetAttribute('fillColor', $androidNs)))
        try {
            $tokens = [regex]::Matches($node.GetAttribute('pathData', $androidNs), '[MLCZ]|-?\d+(?:\.\d+)?') | ForEach-Object { $_.Value }
            $index = 0
            [float]$x = 0; [float]$y = 0
            while ($index -lt $tokens.Count) {
                $command = $tokens[$index++]
                switch ($command) {
                    'M' { $x=[float]$tokens[$index++]; $y=[float]$tokens[$index++]; $path.StartFigure() }
                    'L' {
                        $nextX=[float]$tokens[$index++]; $nextY=[float]$tokens[$index++]
                        $path.AddLine($x,$y,$nextX,$nextY); $x=$nextX; $y=$nextY
                    }
                    'C' {
                        $x1=[float]$tokens[$index++]; $y1=[float]$tokens[$index++]
                        $x2=[float]$tokens[$index++]; $y2=[float]$tokens[$index++]
                        $nextX=[float]$tokens[$index++]; $nextY=[float]$tokens[$index++]
                        $path.AddBezier($x,$y,$x1,$y1,$x2,$y2,$nextX,$nextY); $x=$nextX; $y=$nextY
                    }
                    'Z' { $path.CloseFigure() }
                    default { throw "Unsupported vector command: $command" }
                }
            }
            $graphics.FillPath($brush, $path)
        } finally { $brush.Dispose(); $path.Dispose() }
    }
    $icon.Save((Join-Path $destinationPath 'app-icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally { $graphics.Dispose(); $icon.Dispose() }

Get-ChildItem -LiteralPath $destinationPath -Filter '*.png' | ForEach-Object {
    $image = [System.Drawing.Image]::FromFile($_.FullName)
    try { Write-Output "$($_.Name): $($image.Width)x$($image.Height), $($_.Length) bytes" }
    finally { $image.Dispose() }
}
