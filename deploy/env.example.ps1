# ---------------------------------------------------------------------------
# BSSA Asset Management - configuration template.
#
# This file holds PLACEHOLDERS ONLY and is safe to commit. It is not read by
# the application. Copy it beside itself, fill in the real values, and let the
# copy be what the scripts load:
#
#     Copy-Item deploy\env.example.ps1 deploy\env.local.ps1
#     notepad deploy\env.local.ps1
#
# deploy\env.local.ps1 is listed in .gitignore and must never be committed.
#
# Every value below is an environment variable that Spring Boot reads on its
# own; nothing here is hard-coded into application.properties. Any Spring
# property can be supplied the same way - the name is the property with dots
# and dashes turned into underscores and upper-cased.
# ---------------------------------------------------------------------------

# --- Database connection ---------------------------------------------------
# The application's own defaults (see backend/src/main/resources/application.properties)
# point at localhost, so a server hosting MySQL itself only needs the password.
# SPRING_DATASOURCE_URL overrides the whole JDBC URL, which is what an off-host
# database needs. The username falls back to 'root' when unset; a deployed
# instance should set the least-privilege account described in
# deploy\database-access.sql.example.
$env:DB_USERNAME = 'bssa_asset_app'
$env:DB_PASSWORD = 'set-the-real-password-here'
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/bssa_asset_management'

# Connection tuning that matters on a shared office server. The defaults below
# suit an eight-hour working day; see docs\DEPLOYMENT.md for why they are here.
# $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '10'
# $env:SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT = '30000'
# $env:SPRING_DATASOURCE_HIKARI_MAX_LIFETIME = '1800000'

# --- Web binding -----------------------------------------------------------
# 8080 is the application default. Leave server.address unset so the service
# listens on every interface and office computers can reach it; setting it to
# 127.0.0.1 turns the office deployment into a single-machine tool.
# $env:SERVER_PORT = '8080'
# $env:SERVER_ADDRESS = '0.0.0.0'

# --- First System Admin account -------------------------------------------
# The bootstrap runs only when enabled AND the users table is empty. Leave it
# disabled for ordinary starts and re-enable it for the very first launch.
# BOOTSTRAP_ADMIN_DEPARTMENT_ID must name a row that already exists in
# departments - schema.sql does not seed departments, so create one first.
$env:BOOTSTRAP_ADMIN_ENABLED = 'false'
$env:BOOTSTRAP_ADMIN_EMPLOYEE_CODE = 'SA001'
$env:BOOTSTRAP_ADMIN_FIRST_NAME = 'First'
$env:BOOTSTRAP_ADMIN_LAST_NAME = 'Last'
$env:BOOTSTRAP_ADMIN_EMAIL = 'someone@company.example'
$env:BOOTSTRAP_ADMIN_PASSWORD = 'set-the-real-password-here'
# $env:BOOTSTRAP_ADMIN_PHONE = ''
$env:BOOTSTRAP_ADMIN_DEPARTMENT_ID = '1'

# --- Backup target (used by deploy\backup-database.ps1) -------------------
$env:BACKUP_DIRECTORY = 'deploy\backups'

Write-Host 'Template values loaded. Replace them in deploy\env.local.ps1 - this file sets placeholders only.'
