# Registers the "Troop 82 TV self-heal" task: runs C:\tcl\heal.sh daily at 7:45 AM,
# 12:15 PM and 6 PM (waking the PC) and at sign-in, hidden, and catches up if the PC was off at the scheduled time.
# Re-run this script any time; it replaces the existing task.
$bash = 'C:\Program Files\Git\bin\bash.exe'
$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument "--headless `"$bash`" /c/tcl/heal.sh" -WorkingDirectory 'C:\tcl'
$triggers = @(
    (New-ScheduledTaskTrigger -Daily -At 7:45AM),
    (New-ScheduledTaskTrigger -Daily -At 12:15PM),
    (New-ScheduledTaskTrigger -Daily -At 6:00PM),
    (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME)
)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -MultipleInstances IgnoreNew
$desc = 'Re-applies the Troop 82 TV setup if a TCL update changed anything. Log: C:\tcl\heal.log'
# Preferred: run whether or not anyone is signed in (S4U, no stored password). That needs an
# elevated PowerShell ("Run as administrator"); otherwise fall back to "only when signed in".
try {
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
    Register-ScheduledTask -TaskName 'Troop 82 TV self-heal' -Action $action -Trigger $triggers -Settings $settings `
        -Principal $principal -Description $desc -Force -ErrorAction Stop | Out-Null
    'Registered: runs even when nobody is signed in.'
} catch {
    Register-ScheduledTask -TaskName 'Troop 82 TV self-heal' -Action $action -Trigger $triggers -Settings $settings `
        -Description $desc -Force | Out-Null
    'Registered: runs while you are signed in. (Re-run this script as administrator to run it even when signed out.)'
}
Get-ScheduledTask -TaskName 'Troop 82 TV self-heal' | Select-Object TaskName, State
