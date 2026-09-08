function Get-NativeTestSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [AllowEmptyString()] [string]$Output,
        [Parameter(Mandatory)] [ValidateRange(1, 10000)] [int]$ExpectedTests,
        [Parameter(Mandatory)] [int]$ExitCode
    )

    $success = [regex]::Match($Output, '(?m)^OK \((\d+) tests?\)[ \t]*\r?$')
    $failure = [regex]::Match($Output, '(?m)^Tests run:[ \t]*(\d+),[ \t]*Failures:[ \t]*(\d+)[ \t]*\r?$')
    $failureSignal = $Output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|(?m)^INSTRUMENTATION_STATUS_CODE: -[1-4][ \t]*\r?$'
    $executed = if ($failure.Success) { [int]$failure.Groups[1].Value }
        elseif ($success.Success) { [int]$success.Groups[1].Value }
        else { $null }
    $failed = if ($failure.Success) { [int]$failure.Groups[2].Value }
        elseif ($success.Success -and -not $failureSignal) { 0 }
        else { $null }
    [pscustomobject]@{
        executedTests = $executed
        failedTests = $failed
        outcome = if (
            $ExitCode -eq 0 -and $success.Success -and -not $failure.Success -and
            $executed -eq $ExpectedTests -and -not $failureSignal
        ) { 'PASS' } else { 'FAIL' }
    }
}
