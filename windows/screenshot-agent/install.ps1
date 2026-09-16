$ErrorActionPreference = 'Stop'
$agentRoot = $PSScriptRoot
$agentPython = Join-Path $agentRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $agentPython)) {
    python -m venv (Join-Path $agentRoot '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11以降をインストールしてください。' }
}
& $agentPython -m pip install -r (Join-Path $agentRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw '依存パッケージのインストールに失敗しました。' }
$agentShell = New-Object -ComObject WScript.Shell
$agentShortcut = $agentShell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Lifelog PC.lnk'))
$agentShortcut.TargetPath = Join-Path $agentRoot '.venv/Scripts/pythonw.exe'
$agentShortcut.Arguments = '"' + (Join-Path $agentRoot 'agent.py') + '"'
$agentShortcut.WorkingDirectory = $agentRoot
$agentShortcut.Description = 'PCの画面を定期的に記録'
$agentShortcut.Save()
& $agentPython (Join-Path $agentRoot 'agent.py') --doctor
Write-Output 'デスクトップの Lifelog PC から起動してください。自動起動はアプリの設定で有効にできます。'
