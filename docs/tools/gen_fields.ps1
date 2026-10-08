# 从源码生成 docs/配置项说明.md
#
# 用法（在仓库任意位置执行都可以，路径相对本脚本解析）：
#   powershell -ExecutionPolicy Bypass -File docs\tools\gen_fields.ps1
#
# 注意：本文件必须保存为 UTF-8 with BOM。
# Windows PowerShell 5.1 读取无 BOM 的 .ps1 时会按系统 ANSI（中文系统为 GBK）解码，
# 脚本里的中文会导致解析失败（Missing ')' in method call）。改完务必确认 BOM 还在。
#
# 生成规则：
#   - 分组名取自 data\ModelGroup.java 的枚举，模块顺序取自 model\base\ModelOrder.java 的 clazzList
#   - 逐行匹配 `new XxxModelField(`，按括号配对拼回多行声明，再按顶层逗号切参数
#   - IntegerModelField 取第 3/4/5 个参数拼「默认 x，范围 a~b」
#   - setDependsOn("x") 记为依赖列

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$root = Join-Path $repoRoot 'app\src\main\java\io\github\aw1y2z\sesame'
$outFile = Join-Path $repoRoot 'docs\配置项说明.md'

function Get-Balance([string]$s) {
    $n = 0
    foreach ($c in $s.ToCharArray()) { if ($c -eq '(') { $n++ } elseif ($c -eq ')') { $n-- } }
    return $n
}

function Split-TopLevel([string]$s) {
    $parts = New-Object System.Collections.ArrayList
    $depth = 0; $cur = ''
    foreach ($c in $s.ToCharArray()) {
        if ($c -eq '(' -or $c -eq '<' -or $c -eq '[') { $depth++ }
        elseif ($c -eq ')' -or $c -eq '>' -or $c -eq ']') { $depth-- }
        if ($c -eq ',' -and $depth -eq 0) { [void]$parts.Add($cur.Trim()); $cur = '' }
        else { $cur += $c }
    }
    if ($cur.Trim() -ne '') { [void]$parts.Add($cur.Trim()) }
    return $parts
}

function Join-Str([string]$s) {
    $t = $s
    for ($i = 0; $i -lt 4; $i++) { $t = $t -replace '"\s*\+\s*"', '' }
    return $t
}

function Unquote([string]$s) {
    $t = (Join-Str $s).Trim()
    if ($t.StartsWith('"') -and $t.EndsWith('"') -and $t.Length -ge 2) { return $t.Substring(1, $t.Length - 2) }
    return $t
}

function Eval-Num([string]$s) {
    $t = $s.Trim()
    $m = [regex]::Match($t, '^(\d+)\s*\*\s*(\d+)$')
    if ($m.Success) { return ([int]$m.Groups[1].Value * [int]$m.Groups[2].Value).ToString() }
    return $t
}

function Get-TextConst([string]$text, [string]$ident) {
    $m = [regex]::Match($text, '(?m)^\s*(?:private|public|protected)?\s*(?:static)?\s*(?:final)?\s*String\s+' + [regex]::Escape($ident) + '\s*=\s*([^;]+);')
    if ($m.Success) { return (Unquote $m.Groups[1].Value) }
    return $null
}

function Get-ModelName([string]$text, [string]$fallback) {
    $m = [regex]::Match($text, 'getName\s*\(\s*\)\s*\{[^}]*?return\s+([^;]+);')
    if ($m.Success) {
        $expr = $m.Groups[1].Value.Trim()
        if ($expr.StartsWith('"')) { return (Unquote $expr) }
        if ($expr -match '^[A-Za-z_]\w*$') {
            $c = Get-TextConst $text $expr
            if ($c) { return $c }
        }
    }
    return $fallback
}

function Fmt-Default([string]$s) {
    $t = (Join-Str $s).Trim()
    if ($t -match '^new\s+(Linked)?Hash(Set|Map)<') { return [char]0x7A7A }
    $m = [regex]::Match($t, '^ListUtil\.newArrayList\((.*)\)$')
    if ($m.Success) { return (Join-Str ($m.Groups[1].Value -replace '"', '')) }
    return (Unquote $t)
}

# ---------- group code -> name ----------
$groupName = @{}
foreach ($l in (Get-Content -LiteralPath (Join-Path $root 'data\ModelGroup.java') -Encoding UTF8)) {
    if ($l -match ',?\s*[A-Z_]+\("([A-Z_]+)",\s*"([^"]*)"') { $groupName[$matches[1]] = $matches[2] }
}

# ---------- model order ----------
$order = New-Object System.Collections.ArrayList
foreach ($l in (Get-Content -LiteralPath (Join-Path $root 'model\base\ModelOrder.java') -Encoding UTF8)) {
    if ($l -match 'clazzList\.add\((\w+)\.class\)') { [void]$order.Add($matches[1]) }
}

$fileOf = @{}
Get-ChildItem -LiteralPath (Join-Path $root 'model') -Recurse -Filter *.java | ForEach-Object {
    $fileOf[$_.BaseName] = $_.FullName
}

$zero = [char]0x7A7A
$out = New-Object System.Collections.ArrayList
[void]$out.Add('# 配置项说明（按模块）')
[void]$out.Add('')
[void]$out.Add('> 本文件由 `docs/tools/gen_fields.ps1` 从源码中的字段声明自动生成，顺序与源码声明一致（与配置页显示顺序基本一致）。')
[void]$out.Add('> 修改源码配置项后重新生成：`powershell -ExecutionPolicy Bypass -File docs/tools/gen_fields.ps1`')
[void]$out.Add('> 分组与模型顺序取自 `ModelGroup` / `ModelOrder`。')
[void]$out.Add('>')
[void]$out.Add('> - 「默认 / 范围」里的枚举常量（如 `DonationType.ZERO`）是源码里的取值名，对应配置页下拉框的选项。')
[void]$out.Add('> - 「依赖」列表示该项依赖的开关，父开关关闭时该项不生效。')
[void]$out.Add('> - 同一模型内字段顺序与配置页一致；不同分组的模型排列顺序由 `ModelOrder` 决定。')
[void]$out.Add('')

$rows = 0
foreach ($cls in $order) {
    if (-not $fileOf.ContainsKey($cls)) { continue }
    $lines = Get-Content -LiteralPath $fileOf[$cls] -Encoding UTF8
    $text = ($lines -join "`n")

    $modelName = Get-ModelName $text $cls

    $grp = ''
    $g = [regex]::Match($text, 'getGroup\s*\(\s*\)\s*\{[^}]*?ModelGroup\.([A-Z_]+)')
    if (-not $g.Success) { $g = [regex]::Match($text, 'ModelGroup\.([A-Z_]+)') }
    if ($g.Success) { $grp = $g.Groups[1].Value }
    $grpLabel = if ($groupName.ContainsKey($grp)) { $groupName[$grp] } else { $grp }

    [void]$out.Add("## $grpLabel | $modelName ($cls)")
    [void]$out.Add('')
    # 表格行先攒着：有字段才补表头，避免出现只有表头的空表（如小镇模块无独立配置项）
    $tbl = New-Object System.Collections.ArrayList

    $count = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -notmatch 'new\s+[\w.]+ModelField\s*\(') { continue }
        if ($line -match '^\s*(//|\*)') { continue }

        $buf = $line
        $j = $i
        while ((Get-Balance $buf) -gt 0 -and $j -lt ($lines.Count - 1)) {
            $j++
            $buf += ' ' + $lines[$j]
        }

        $fm = [regex]::Match($buf, 'new\s+([\w.]+ModelField)\s*\(')
        if (-not $fm.Success) { continue }
        $typeName = $fm.Groups[1].Value
        $start = $fm.Index + $fm.Length

        $depth = 1; $k = $start; $argStr = ''
        while ($k -lt $buf.Length -and $depth -gt 0) {
            $ch = $buf[$k]
            if ($ch -eq '(') { $depth++ }
            elseif ($ch -eq ')') { $depth--; if ($depth -eq 0) { break } }
            $argStr += $ch
            $k++
        }
        $args = Split-TopLevel $argStr
        if ($args.Count -lt 2) { continue }

        $code = Unquote $args[0]
        $label = Unquote $args[1]
        if ($code -eq '' -or $label -eq '') { continue }

        $def = $zero
        if ($typeName -match 'Integer') {
            if ($args.Count -ge 5) { $def = "默认 " + (Eval-Num (Unquote $args[2])) + "，范围 " + (Eval-Num (Unquote $args[3])) + "~" + (Eval-Num (Unquote $args[4])) }
            elseif ($args.Count -ge 3) { $def = "默认 " + (Eval-Num (Unquote $args[2])) }
        }
        elseif ($args.Count -ge 3) {
            $d = Fmt-Default $args[2]
            if ($d -ne '') { $def = '默认 ' + $d }
        }

        $dep = ''
        $dm = [regex]::Match($buf, 'setDependsOn\("([^"]*)"\)')
        if ($dm.Success) { $dep = '`' + $dm.Groups[1].Value + '`' }

        $codeCell = '`' + ($code -replace '\|', '\|') + '`'
        $label = $label -replace '\|', '\|'
        $def = $def -replace '\|', '\|'
        [void]$tbl.Add("| $codeCell | $label | $def | $dep |")
        $count++
        $i = $j
    }
    if ($tbl.Count -gt 0) {
        [void]$out.Add('| 配置项 (code) | 显示名称 | 默认 / 范围 | 依赖 |')
        [void]$out.Add('| --- | --- | --- | --- |')
        [void]$out.AddRange([string[]]$tbl)
    } else {
        [void]$out.Add('> 本模块无独立配置项，只有继承自 `Model` 的「开启」开关。')
    }
    [void]$out.Add('')
    $rows += $count
    Write-Host ("{0,-18} {1,4}  {2}" -f $cls, $count, $modelName)
}

# 手工维护的小节（脚本扫不到 AppConfig 的普通字段）：从原文件原样搬回，插入位置沿用「它后面那个标题」
$keep = @()
$anchor = ''
if (Test-Path -LiteralPath $outFile) {
    $old = [System.IO.File]::ReadAllLines($outFile, [System.Text.Encoding]::UTF8)
    for ($i = 0; $i -lt $old.Count; $i++) {
        if ($old[$i] -ne '## 全局配置 (AppConfig，不分账号)') { continue }
        for ($j = $i; $j -lt $old.Count; $j++) {
            if ($j -gt $i -and $old[$j].StartsWith('## ')) { $anchor = $old[$j]; break }
            $keep += $old[$j]
        }
        break
    }
}
if ($keep.Count -gt 0) {
    $at = $out.Count
    for ($k = 0; $k -lt $out.Count; $k++) { if ($out[$k] -eq $anchor) { $at = $k; break } }
    [void]$out.InsertRange($at, [string[]]$keep)
    Write-Host ("kept section: " + $keep.Count + " lines, insert at " + $at)
}

$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($outFile, $out.ToArray(), $enc)
Write-Host ("written: " + $outFile + "  lines=" + $out.Count + "  rows=" + $rows)
