# 本地后端启动（PowerShell 版）
# 用法：powershell -ExecutionPolicy Bypass -File scripts/start-local-server.ps1
# 读取 server/.env 凭据（DB_USER/DB_PASS/JWT_SECRET）注入环境变量后启动
$ErrorActionPreference = "Stop"
$serverDir = Join-Path $PSScriptRoot "..\server"

$envFile = Join-Path $serverDir ".env"
if (-not (Test-Path $envFile)) {
    Write-Error "缺少 server/.env（先用 openssl rand -base64 48 生成 JWT_SECRET 写入）"
    exit 1
}

# 解析 .env（简单 KEY=VALUE，忽略 # 注释）
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $idx = $line.IndexOf("=")
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
        Set-Item -Path "env:$key" -Value $val
    }
}

Write-Host "已注入 DB_USER/DB_PASS/JWT_SECRET，启动 Spring Boot..." -ForegroundColor Green
Push-Location $serverDir
try {
    mvn spring-boot:run
} finally {
    Pop-Location
}