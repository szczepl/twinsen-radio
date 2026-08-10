# Okienko podgladu metadanych - pokazuje na biezaco, co aplikacja wysyla do
# sesji medialnej i co przychodzi w strumieniu ICY z rozglosni.
#
# Czyta `adb logcat` po tagach IcyMeta i MetaDump. Uruchamiane automatycznie
# razem z dhu.bat, mozna tez odpalic osobno:
#
#   powershell -ExecutionPolicy Bypass -File tools\meta-watch.ps1
#
# Uwaga: plik celowo bez polskich znakow - Windows PowerShell 5.1 czyta skrypty
# bez BOM jako ANSI i diakrytyki rozwalaja parser.

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$adb = 'C:\Android\Sdk\platform-tools\adb.exe'

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Twinsen Radio - podglad metadanych'
$form.Size = New-Object System.Drawing.Size(620, 780)
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
$statusLabel.Text = 'Startuje...'
[void]$status.Items.Add($statusLabel)
$form.Controls.Add($status)

# Kolejka miedzy watkiem czytajacym logcat a watkiem UI
$queue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $adb
$psi.Arguments = 'logcat -v time -s IcyMeta:I MetaDump:I RadioService:I'
$psi.RedirectStandardOutput = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$proc.EnableRaisingEvents = $true

$onData = {
    param($s, $e)
    if ($e.Data) { $queue.Enqueue($e.Data) }
}
Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $onData | Out-Null

# wyczysc bufor, zeby nie wysypac historii przy starcie
& $adb logcat -c 2>$null
[void]$proc.Start()
$proc.BeginOutputReadLine()

$lineCount = 0
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 400
$timer.Add_Tick({
    $line = $null
    $added = $false
    while ($queue.TryDequeue([ref]$line)) {
        # logcat: "MM-DD hh:mm:ss.mmm I/Tag( pid): tresc"
        $m = [regex]::Match($line, '^(\d\d-\d\d )?(\d\d:\d\d:\d\d)\.\d+\s+\w/(\w+)\s*\(\s*\d+\):\s?(.*)$')
        if (-not $m.Success) { continue }
        $time = $m.Groups[2].Value
        $tag  = $m.Groups[3].Value
        $text = $m.Groups[4].Value

        $color = switch ($tag) {
            'IcyMeta'  { [System.Drawing.Color]::FromArgb(120, 220, 255) }
            'MetaDump' { if ($text.StartsWith('---')) { [System.Drawing.Color]::FromArgb(255, 210, 100) } else { [System.Drawing.Color]::Gainsboro } }
            default    { [System.Drawing.Color]::FromArgb(140, 140, 140) }
        }

        $box.SelectionStart = $box.TextLength
        $box.SelectionLength = 0
        $box.SelectionColor = [System.Drawing.Color]::FromArgb(90, 90, 90)
        $box.AppendText("$time  ")
        $box.SelectionColor = $color
        $box.AppendText("$text`n")
        $script:lineCount++
        $added = $true
    }
    if ($added) {
        # przytnij, zeby okno nie puchlo przez godziny obserwacji
        if ($script:lineCount -gt 4000) {
            $box.Clear()
            $script:lineCount = 0
        }
        $box.SelectionStart = $box.TextLength
        $box.ScrollToCaret()
        $statusLabel.Text = "Linii: $script:lineCount    (niebieskie = ICY ze strumienia, zolte = nowy zestaw metadanych)"
    }
})
$timer.Start()

$form.Add_FormClosing({
    $timer.Stop()
    if (-not $proc.HasExited) { $proc.Kill() }
})

[void]$form.ShowDialog()
