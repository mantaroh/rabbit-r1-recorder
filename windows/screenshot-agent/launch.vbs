Set shell = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
folder = fs.GetParentFolderName(WScript.ScriptFullName)
python = folder & "\.venv\Scripts\pythonw.exe"
If Not fs.FileExists(python) Then
  MsgBox "Please run install.ps1 first.", 48, "Lifelog"
Else
  shell.Run Chr(34) & python & Chr(34) & " " & Chr(34) & folder & "\agent.py" & Chr(34), 0, False
End If
