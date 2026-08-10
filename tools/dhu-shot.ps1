# Zrzuca zawartosc okien Desktop Head Unit BEZ przejmowania focusu.
#
# Po co: klikanie SetForegroundWindow wyrywa uzytkownikowi aktywne okno w trakcie
# pracy. PrintWindow z flaga PW_RENDERFULLCONTENT rysuje okno do wlasnego bufora,
# nawet gdy jest przyslonione i nieaktywne.
#
# DHU renderuje przez DirectX i czasem oddaje czarna klatke - wtedy skrypt
# wycina obszar okna ze zrzutu calego ekranu (to tez nie wymaga focusu, ale
# widac to, co faktycznie jest na wierzchu).
#
#   .\tools\dhu-shot.ps1 -OutDir C:\sciezka

param([string]$OutDir = "$env:TEMP\dhu-shots")

Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WinCap {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

New-Item -ItemType Directory -Force $OutDir | Out-Null

$pids = @(Get-Process -Name desktop-head-unit -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
if (-not $pids) { Write-Host 'DHU nie dziala.'; exit 1 }

$handles = New-Object System.Collections.ArrayList
$cb = [WinCap+EnumProc]{
    param($h, $p)
    if ([WinCap]::IsWindowVisible($h)) {
        [uint32]$owner = 0
        [void][WinCap]::GetWindowThreadProcessId($h, [ref]$owner)
        if ($pids -contains [int]$owner) { [void]$handles.Add($h) }
    }
    return $true
}
[void][WinCap]::EnumWindows($cb, [IntPtr]::Zero)

# jeden zrzut calego ekranu na potrzeby awaryjnego wycinania
$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
$screen = New-Object System.Drawing.Bitmap($vs.Width, $vs.Height)
$sg = [System.Drawing.Graphics]::FromImage($screen)
$sg.CopyFromScreen($vs.Left, $vs.Top, 0, 0, $screen.Size)
$sg.Dispose()

$i = 0
foreach ($h in $handles) {
    $sb = New-Object System.Text.StringBuilder 256
    [void][WinCap]::GetWindowText($h, $sb, 256)
    $title = $sb.ToString()
    if (-not $title) { continue }

    $r = New-Object WinCap+RECT
    [void][WinCap]::GetWindowRect($h, [ref]$r)
    $w = $r.Right - $r.Left; $hgt = $r.Bottom - $r.Top
    if ($w -le 0 -or $hgt -le 0) { continue }

    $bmp = New-Object System.Drawing.Bitmap($w, $hgt)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $g.GetHdc()
    [void][WinCap]::PrintWindow($h, $hdc, 2)   # PW_RENDERFULLCONTENT
    $g.ReleaseHdc($hdc)
    $g.Dispose()

    # sprawdz, czy PrintWindow nie oddal czarnej klatki (typowe dla DirectX)
    $probe = 0
    for ($x = 10; $x -lt [Math]::Min($w, 400); $x += 40) {
        for ($y = 10; $y -lt [Math]::Min($hgt, 400); $y += 40) {
            $c = $bmp.GetPixel($x, $y)
            $probe += $c.R + $c.G + $c.B
        }
    }
    $method = 'PrintWindow'
    if ($probe -lt 200) {
        $bmp.Dispose()
        $bmp = New-Object System.Drawing.Bitmap($w, $hgt)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.DrawImage($screen, (New-Object System.Drawing.Rectangle(0, 0, $w, $hgt)),
                     (New-Object System.Drawing.Rectangle(($r.Left - $vs.Left), ($r.Top - $vs.Top), $w, $hgt)),
                     [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()
        $method = 'wyciety ze zrzutu ekranu'
    }

    $safe = ($title -replace '[^\w\-]', '_')
    $path = Join-Path $OutDir ("{0:d2}_{1}.png" -f $i, $safe)
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    "{0,-34} {1,5}x{2,-5} {3,-24} {4}" -f $title, $w, $hgt, $method, $path
    $i++
}
$screen.Dispose()
