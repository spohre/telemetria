@echo off
rem ==========================================================================
rem  Gera distribuicao\telemetria.jar (+ lib\h2) a partir do codigo
rem  atual. Usa so o que ja existe na maquina de desenvolvimento: Maven (para
rem  compilar), a ferramenta "jar" do JDK e o H2 que o Maven ja baixou em .m2.
rem  Os executaveis Telemetria.exe / Telemetria-Console.exe ja estao prontos na
rem  pasta e nao mudam (codigo-fonte em lancador\).
rem
rem  Uso: empacotar.bat        (na pasta do projeto)
rem ==========================================================================
setlocal
cd /d "%~dp0"

set "DEST=distribuicao"
set "H2=%USERPROFILE%\.m2\repository\com\h2database\h2\2.3.232\h2-2.3.232.jar"
set "JAR=jar"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JAR=%JAVA_HOME%\bin\jar.exe"

echo [1/3] Compilando para Java 17...
if exist target\classes rmdir /s /q target\classes
call mvn -q compile
if errorlevel 1 goto erro

if not exist "%H2%" (
  echo H2 nao encontrado em "%H2%".
  echo Rode "mvn test" uma vez para o Maven baixa-lo e tente de novo.
  goto erro
)

echo [2/3] Gerando telemetria.jar...
if not exist "%DEST%\lib" mkdir "%DEST%\lib"
set "MANIFESTO=%TEMP%\telemetria-manifesto.txt"
> "%MANIFESTO%" echo Main-Class: app.Main
>> "%MANIFESTO%" echo Class-Path: lib/h2-2.3.232.jar
>> "%MANIFESTO%" echo Implementation-Title: Central de Telemetria
"%JAR%" --create --file "%DEST%\telemetria.jar" --manifest "%MANIFESTO%" -C target\classes .
if errorlevel 1 goto erro
copy /y "%H2%" "%DEST%\lib\" >nul
if errorlevel 1 goto erro

echo [3/3] Pronto: %DEST%
echo        Abra %DEST%\Telemetria.exe ou copie a pasta %DEST% para outra maquina.
endlocal
exit /b 0

:erro
echo.
echo Falhou. Veja a mensagem acima.
endlocal
exit /b 1
