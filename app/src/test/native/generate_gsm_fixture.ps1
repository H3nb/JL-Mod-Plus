# SPDX-License-Identifier: Apache-2.0
param([Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
# Microsoft GSM packs two 260-bit frames least-significant-bit first in 65 bytes.
# Every field below is in range: neutral LAR, lag 40, gain/grid 0, xmaxc 32,
# and alternating 3/4 pulse codes. This is a synthetic signal, not a recording.
$block = [byte[]]::new(65)
function Set-GsmBits([int]$Offset, [int]$Value, [int]$Count) {
    for ($bit = 0; $bit -lt $Count; $bit++) {
        if (($Value -shr $bit) -band 1) {
            $index = [int][Math]::Floor(($Offset + $bit) / 8)
            $block[$index] = $block[$index] -bor (1 -shl (($Offset + $bit) % 8))
        }
    }
    return $Offset + $Count
}
$offset = 0
for ($frame = 0; $frame -lt 2; $frame++) {
    foreach ($width in @(6,6,5,5,4,4,3,3)) { $offset = Set-GsmBits $offset (1 -shl ($width - 1)) $width }
    for ($subframe = 0; $subframe -lt 4; $subframe++) {
        $offset = Set-GsmBits $offset 40 7
        $offset = Set-GsmBits $offset 0 2
        $offset = Set-GsmBits $offset 0 2
        $offset = Set-GsmBits $offset 32 6
        for ($pulse = 0; $pulse -lt 13; $pulse++) { $offset = Set-GsmBits $offset (3 + ($pulse % 2)) 3 }
    }
}
$file = [IO.File]::Create($Output)
$writer = [IO.BinaryWriter]::new($file)
try {
    $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $writer.Write([uint32]1678)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt ')); $writer.Write([uint32]20)
    foreach ($number in @(49,1)) { $writer.Write([uint16]$number) }
    $writer.Write([uint32]8000); $writer.Write([uint32]1625)
    foreach ($number in @(65,0,2,320)) { $writer.Write([uint16]$number) }
    $writer.Write([Text.Encoding]::ASCII.GetBytes('fact')); $writer.Write([uint32]4); $writer.Write([uint32]8000)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('data')); $writer.Write([uint32]1625)
    for ($packet = 0; $packet -lt 25; $packet++) { $writer.Write($block) }
    $writer.Write([byte]0)
} finally { $writer.Dispose() }
