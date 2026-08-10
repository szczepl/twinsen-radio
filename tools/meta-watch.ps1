# Okienko podgladu metadanych - pokazuje na biezaco, co rozglosnia wysyla
# w strumieniu ICY i co aplikacja wystawia do sesji medialnej.
#
# Uruchamiane automatycznie przez dhu.bat, mozna tez odpalic osobno:
#   powershell -ExecutionPolicy Bypass -File tools\meta-watch.ps1
#
# Implementacja: timer w oknie doczytuje stdout procesu `adb logcat` przez
# Peek()/ReadLine(), wiec nie blokuje UI. Dwie wczesniejsze wersje nie dzialaly:
# Register-ObjectEvent (scriptblock zdarzenia chodzi we wlasnym runspace i nie
# widzial kolejki, wiec linie znikaly bez sladu) oraz przekierowanie logcata do
# pliku (procesy mialy rozne zmienne srodowiskowe i plik ladowal gdzie indziej).
#
# Plik celowo bez polskich znakow: Windows PowerShell 5.1 czyta skrypty bez BOM
# jako ANSI i diakrytyki rozwalaja parser.

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$adb = 'C:\Android\Sdk\platform-tools\adb.exe'

& $adb logcat -c 2>$null

# Czytamy stdout logcata wprost z procesu. Dwie wczesniejsze wersje nie dzialaly:
# Register-ObjectEvent (scriptblock w innym runspace nie widzial kolejki) oraz
# przekierowanie do pliku (plik ladowal gdzie indziej, bo procesy mialy rozne
# zmienne srodowiskowe). Tu nie ma ani zdarzen, ani plikow posrednich.
$startError = $null
$proc = $null
try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    $psi.Arguments = 'logcat -v time -s IcyMeta:I MetaDump:I'
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
} catch {
    $startError = $_.Exception.Message
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Twinsen Radio - podglad metadanych'
$form.Size = New-Object System.Drawing.Size(660, 820)
$form.StartPosition = 'Manual'
$form.Location = New-Object System.Drawing.Point(20, 20)
$form.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 20)

$box = New-Object System.Windows.Forms.RichTextBox
$box.Dock = 'Fill'
$box.ReadOnly = $true
$box.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 20)
$box.ForeColor = [System.Drawing.Color]::Gainsboro
$box.Font = New-Object System.Drawing.Font('Consolas', 10)
$box.WordWrap = $false
$box.ScrollBars = 'Both'
$form.Controls.Add($box)

$status = New-Object System.Windows.Forms.StatusStrip
$statusLabel = New-Object System.Windows.Forms.ToolStripStatusLabel
$statusLabel.Text = if ($startError) { "BLAD startu logcat: $startError" } else { 'Czekam na dane... wlacz stacje w aplikacji' }
if ($startError) {
    $box.SelectionColor = [System.Drawing.Color]::Salmon
    $box.AppendText("Nie udalo sie uruchomic adb logcat:`n$startError`n")
}
[void]$status.Items.Add($statusLabel)
$form.Controls.Add($status)

$script:lines = 0
$script:icyCount = 0

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 500
$timer.Add_Tick({
    if (-not $proc -or $proc.HasExited) { return }

    # Peek() nie blokuje - czytamy tylko to, co juz jest w buforze
    $batch = New-Object System.Collections.ArrayList
    while ($proc.StandardOutput.Peek() -ge 0) {
        $l = $proc.StandardOutput.ReadLine()
        if ($null -eq $l) { break }
        [void]$batch.Add($l)
        if ($batch.Count -gt 400) { break }   # nie blokuj UI na dlugo
    }
    if ($batch.Count -eq 0) { return }

    foreach ($line in $batch) {
        if (-not $line.Trim()) { continue }
        $m = [regex]::Match($line, '^\d\d-\d\d\s+(\d\d:\d\d:\d\d)\.\d+\s+\w/(\w+)\s*\(\s*\d+\)\s*:\s?(.*)$')
        if (-not $m.Success) { continue }
        $time = $m.Groups[1].Value
        $tag  = $m.Groups[2].Value
        $text = $m.Groups[3].Value

        $color = [System.Drawing.Color]::Gainsboro
        if ($tag -eq 'IcyMeta') {
            $script:icyCount++
            $color = if ($text -match '^(==|surowy blok|po )') {
                [System.Drawing.Color]::FromArgb(120, 220, 255)
            } else {
                [System.Drawing.Color]::FromArgb(90, 175, 210)
            }
        } elseif ($text -match '^---') {
            $color = [System.Drawing.Color]::FromArgb(255, 205, 90)
        }

        $box.SelectionStart = $box.TextLength
        $box.SelectionLength = 0
        $box.SelectionColor = [System.Drawing.Color]::FromArgb(95, 95, 95)
        $box.AppendText("$time ")
        $box.SelectionColor = $color
        $box.AppendText("$text`n")
        $script:lines++
    }

    if ($script:lines -gt 5000) { $box.Clear(); $script:lines = 0 }
    $box.SelectionStart = $box.TextLength
    $box.ScrollToCaret()
    $statusLabel.Text = "linii: $script:lines   zdarzen ICY: $script:icyCount   (niebieskie = ze strumienia, zolte = nasze metadane)"
})
$timer.Start()

$form.Add_FormClosing({
    $timer.Stop()
    if ($proc -and -not $proc.HasExited) { $proc.Kill() }
    Get-CimInstance Win32_Process -Filter "Name='adb.exe'" |
        Where-Object { $_.CommandLine -match 'IcyMeta' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
})

[void]$form.ShowDialog()

