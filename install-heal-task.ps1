# Registers the "Troop 82 TV self-heal" task: runs C:\tcl\heal.sh daily at 7:45 AM and
# at sign-in, hidden, and catches up if the PC was off at the scheduled time.
# Re-run this script any time; it replaces the existing task.
$bash = 'C:\Program Files\Git\bin\bash.exe'
$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument "--headless `"$bash`" /c/tcl/heal.sh" -WorkingDirectory 'C:\tcl'
$triggers = @(
    (New-ScheduledTaskTrigger -Daily -At 7:45AM),
    (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME)
)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'Troop 82 TV self-heal' -Action $action -Trigger $triggers -Settings $settings `
    -Description 'Re-applies the Troop 82 TV setup if a TCL update changed anything. Log: C:\tcl\heal.log' -Force | Out-Null
Get-ScheduledTask -TaskName 'Troop 82 TV self-heal' | Select-Object TaskName, State
