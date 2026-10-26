#!/usr/bin/env bash
# =====================================================================
# Revisor automático — Sistema de Recepção e Análise de Telemetria
# Uso: bash verificar.sh [raiz-do-projeto]   (padrão: diretório atual)
# Saída: uma linha por verificação → [OK] / [FALHA] / [ATENÇÃO]
# Checagens ESTÁTICAS: complementam (não substituem) a revisão manual.
# =====================================================================
RAIZ="${1:-.}"
cd "$RAIZ" || { echo "Projeto não encontrado: $RAIZ"; exit 2; }

OK=0; FALHA=0; ATENCAO=0
ok()      { echo "[OK]      $1"; OK=$((OK+1)); }
falha()   { echo "[FALHA]   $1"; FALHA=$((FALHA+1)); }
atencao() { echo "[ATENÇÃO] $1"; ATENCAO=$((ATENCAO+1)); }

JAVA=$(find . -path ./target -prune -o -path ./build -prune -o -name "*.java" -print 2>/dev/null)
# código de demonstração/simulação (ex.: app/ui/demo/ControladorSimulado) NÃO conta como requisito
MAIN=$(echo "$JAVA" | grep -v "/test/" | grep -v "/demo/")
TEST=$(echo "$JAVA" | grep "/test/")
[ -z "$JAVA" ] && { echo "Nenhum .java encontrado em $RAIZ"; exit 2; }

grepq()   { [ -n "$2" ] && echo "$2" | xargs grep -lE "$1" 2>/dev/null; }
arquivo() { echo "$MAIN" | grep -E "/$1\.java$" | head -1; }

echo "== 1. Estrutura de pacotes (domain / infra / app)"
for p in domain infra app; do
  echo "$MAIN" | grep -q "/$p/" && ok "pacote $p/ existe" || falha "pacote $p/ ausente"
done

echo; echo "== 2. Classes obrigatórias"
for c in Sensor LeituraTelemetria Alerta AnalisadorDeAnomalias AlertaRepository; do
  f=$(arquivo "$c"); [ -n "$f" ] && ok "$c → $f" || falha "classe $c não encontrada"
done
f=$(arquivo FilaDeLeituras); [ -n "$f" ] && ok "FilaDeLeituras → $f" || atencao "FilaDeLeituras não encontrada (nome sugerido)"
f=$(arquivo LogArquivo);     [ -n "$f" ] && ok "LogArquivo → $f"     || atencao "LogArquivo não encontrada (nome sugerido)"

echo; echo "== 3. Sensores concorrentes (1,2 pt)"
f=$(arquivo Sensor)
if [ -n "$f" ]; then
  grep -qE "implements[^{]*Runnable" "$f" && ok "Sensor implements Runnable" || falha "Sensor não implementa Runnable"
  grep -q "InterruptedException" "$f" && ok "Sensor trata InterruptedException" || atencao "Sensor não trata InterruptedException (encerramento gracioso)"
  grep -qE "catch *\(InterruptedException[^)]*\) *\{ *\}" "$f" && falha "Sensor engole InterruptedException com catch vazio"
  grep -qE "java\.sql|AlertaRepository|LogArquivo|FileWriter|BufferedWriter" "$f" && falha "Sensor conhece banco/log (viola separação)" || ok "Sensor não depende de banco/log"
fi
n=$(grepq "new Thread\(|Executors\.|ExecutorService" "$MAIN" | wc -l)
[ "$n" -gt 0 ] && ok "threads/executores são criados ($n arquivo(s))" || falha "nenhuma criação de Thread/ExecutorService encontrada"

echo; echo "== 4. Fila thread-safe (1,3 pt)"
if grepq "BlockingQueue|LinkedBlockingQueue|ArrayBlockingQueue" "$MAIN" >/dev/null && [ -n "$(grepq 'BlockingQueue' "$MAIN")" ]; then
  ok "usa BlockingQueue"
  grepq "\.offer\(" "$MAIN" >/dev/null && [ -n "$(grepq '\.offer\(' "$MAIN")" ] && atencao "uso de offer(): verifique o retorno — offer() descarta leitura se a fila estiver cheia (use put())"
elif [ -n "$(grepq 'synchronized|ReentrantLock' "$MAIN")" ]; then
  ok "usa sincronização manual (synchronized/Lock)"
  [ -n "$(grepq 'wait\(\)' "$MAIN")" ] && { [ -n "$(grepq 'while *\(' "$(arquivo FilaDeLeituras)")" ] && ok "wait() dentro de while" || atencao "confira se wait() está dentro de while (spurious wakeup)"; }
  [ -n "$(grepq '(^|[^A-Za-z])notify\(\)' "$MAIN")" ] && atencao "notify() em vez de notifyAll(): risco com vários produtores/consumidores"
else
  falha "nenhuma estrutura thread-safe encontrada (BlockingQueue/synchronized/Lock)"
fi
for col in "new ArrayList" "new LinkedList" "new HashMap"; do
  f=$(arquivo FilaDeLeituras); [ -n "$f" ] && grep -q "$col" "$f" && atencao "FilaDeLeituras usa $col — garanta que todo acesso esteja sincronizado"
done

echo; echo "== 5. Persistência JDBC (1,0 pt)"
f=$(arquivo AlertaRepository)
if [ -n "$f" ]; then
  grep -q "PreparedStatement" "$f" && ok "AlertaRepository usa PreparedStatement" || falha "AlertaRepository sem PreparedStatement"
  grep -qE "try *\([^)]*(Connection|getConnection|abrir)" "$f" && ok "Connection em try-with-resources" || atencao "confira se Connection é fechada (try-with-resources)"
  grep -qE '"[^"]*(INSERT|SELECT|WHERE)[^"]*" *\+ *[a-zA-Z]' "$f" && falha "SQL montado por concatenação (risco de SQL injection)"
fi
fora=$(echo "$MAIN" | grep -v -E "AlertaRepository|Repository|Dao|DAO|Conex|Banco|Database|infra/" | xargs grep -lE "INSERT INTO|SELECT .* FROM|java\.sql\.(Connection|PreparedStatement)" 2>/dev/null)
[ -z "$fora" ] && ok "SQL isolado na camada de persistência" || falha "SQL fora do repositório: $fora"
sql=$(find . -path ./target -prune -o -iname "*.sql" -print | head -5)
[ -n "$sql" ] && ok "script SQL de criação: $(echo $sql | tr '\n' ' ')" || falha "script .sql de criação da tabela ausente (entregável)"
[ -n "$sql" ] && { echo "$sql" | xargs grep -qi "create table" && ok "script contém CREATE TABLE" || falha "nenhum CREATE TABLE no(s) .sql"; }
echo "$sql" | xargs grep -qiE "DROP TABLE" 2>/dev/null && atencao "script contém DROP TABLE — alertas não persistirão entre execuções"
find . -path ./target -prune -o -path ./src/test -prune -o \( -name "*.properties" -o -name "*.java" \) -print 2>/dev/null \
  | xargs grep -hE "jdbc:h2:mem:|jdbc:sqlite::memory:" 2>/dev/null | grep -vqE "^[[:space:]]*(\*|//|/\*|#)" \
  && falha "banco EM MEMÓRIA no código principal — alertas somem ao encerrar (requisito exige consulta após encerramento)" \
  || ok "banco principal não é em memória"

echo; echo "== 6. Log em arquivo (I/O)"
n=$(grepq "FileWriter|BufferedWriter|PrintWriter|FileOutputStream|Files\.(write|newBufferedWriter)" "$MAIN" | wc -l)
[ "$n" -gt 0 ] && ok "I/O de arquivo presente" || falha "nenhum writer/stream de arquivo encontrado (log obrigatório)"
log=$(find . -path ./target -prune -o -name "*.log" -print -o -name "*log*.txt" -print | head -3)
[ -n "$log" ] && ok "arquivo de log de exemplo: $(echo $log | tr '\n' ' ')" || atencao "nenhum .log de execução de exemplo encontrado (entregável)"

echo; echo "== 7. Separação domínio × infraestrutura (0,5 pt)"
dom=$(echo "$MAIN" | grep "/domain/")
viol=$(echo "$dom" | xargs grep -lE "import java\.sql|import java\.io\.File|\.infra\.|import java\.nio\.file" 2>/dev/null)
[ -z "$viol" ] && ok "domain/ não importa java.sql, I/O de arquivo nem infra" || falha "domain/ depende de infraestrutura: $viol"

echo; echo "== 8. Testes unitários (0,5 pt — mínimo 5 nas regras de anomalia)"
# só classes de teste que instanciam/usam o analisador em código (ignora menções em comentários)
ta=$( { echo "$TEST" | grep -E "Analisador[^/]*\.java$"; echo "$TEST" | xargs grep -lE "new AnalisadorDeAnomalias|AnalisadorDeAnomalias +[a-z][A-Za-z0-9_]* *[=;]" 2>/dev/null; } | sort -u | grep . )
if [ -n "$ta" ]; then
  n=$(cat $ta | grep -oE "@(Test|ParameterizedTest)\b" | wc -l)
  [ "$n" -ge 5 ] && ok "$n testes cobrindo AnalisadorDeAnomalias" || falha "só $n teste(s) de AnalisadorDeAnomalias (mínimo 5)"
  echo "$ta" | xargs grep -lE "new Thread|Thread\.sleep|ExecutorService|DriverManager|Connection" >/dev/null 2>&1 \
    && falha "testes do analisador usam thread/banco (devem ser isolados)" || ok "testes do analisador isolados de threads e banco"
  grep -q "org.junit.jupiter" $ta && ok "JUnit 5" || atencao "testes não usam JUnit 5 (org.junit.jupiter)"
else
  falha "nenhum teste referencia AnalisadorDeAnomalias"
fi

echo; echo "== 9. Entregáveis"
find . -maxdepth 2 -iname "*relat*" -o -maxdepth 2 -iname "README*" | grep -q . && ok "relatório/README presente" || atencao "relatório não encontrado (regras de anomalia + concorrência)"

echo; echo "======================================================"
echo "RESUMO: $OK OK · $FALHA FALHA · $ATENCAO ATENÇÃO"
[ "$FALHA" -eq 0 ]
