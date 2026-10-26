# Central de Telemetria — Sistema de Recepção e Análise de Telemetria

Sensores simulados, cada um na sua thread, publicam leituras numa fila central thread-safe;
consumidores aplicam as regras de anomalia, gravam os alertas em banco (JDBC/H2) e registram
todas as leituras em arquivo. Interface Swing e modo console.

Relatório e entregáveis: [`entregaveis/`](entregaveis/) · Estresse: [`docs/estresse.md`](docs/estresse.md)

## Executável para Windows (sem Maven)

A pasta [`distribuicao/`](distribuicao/) é o programa pronto: copie a pasta
para qualquer máquina com **Java 17 ou mais novo** e dê dois cliques em **`Telemetria.exe`**.
Nada mais precisa ser baixado — o banco H2 vai junto em `lib/`.

| Arquivo | Para quê |
|---|---|
| `Telemetria.exe` | abre a interface (sem console) |
| `Telemetria-Console.exe` | modo console; sem argumentos roda 10 s e mostra o extrato; aceita as opções `--console ...` |
| `telemetria.jar` + `lib/h2-2.3.232.jar` | o programa; em Linux/macOS: `java -jar telemetria.jar` |
| `LEIA-ME.txt` | instruções para quem recebe a pasta |

O `.exe` é um lançador pequeno (código em [`lancador/`](lancador/), em Go): procura um Java 17+
(`JAVA_HOME`, `PATH` e as pastas de instalação comuns: Oracle, Temurin, Microsoft, Zulu,
Corretto...), usa a própria pasta como diretório de trabalho (onde ficam `data/`, o banco e o log)
e executa o `telemetria.jar`. Se não houver Java 17+, ou se o programa fechar com erro, mostra
uma mensagem explicando o que fazer.

Depois de mudar o código, gere o jar de novo com **`empacotar.bat`** (usa o Maven para compilar,
a ferramenta `jar` do JDK e o H2 que o Maven já baixou — não baixa nada novo). Os `.exe` não
precisam ser refeitos; para isso há instruções em `lancador/main.go` (Go 1.21+).

## Requisitos para desenvolver

- JDK 17 ou mais novo (o código é compilado para Java 17)
- Maven 3.9 ou mais novo (baixa o H2 e o JUnit na primeira execução)

## Compilar e testar

```bash
mvn test                       # 111 testes: unidade + integração com H2 em memória
mvn test -Dgroups=integracao   # só os de banco
mvn test -Dgroups=estresse     # só o estresse: 3 cenários × 5 rodadas (~80 s); fica fora do mvn test
```

## Rodar

```bash
mvn -q compile exec:java                                    # abre a interface
mvn -q compile exec:java "-Dexec.args=--console 10"         # 10 s sem janela, imprime o extrato no fim
mvn -q compile exec:java "-Dexec.args=--console 20 --sensores 10 --intervalo 10 --consumidores 4 --fila 50"
mvn -q compile exec:java -Dexec.mainClass=app.ui.demo.DemoInterface   # tela com dados falsos
```

No PowerShell, mantenha as aspas em volta de `-Dexec.args=...`.

Opções do modo console (todas opcionais): `--sensores N` por tipo (1–10; total = 4 × N),
`--intervalo MS` (10–5000), `--consumidores N` (1–4), `--fila N` (capacidade; padrão 1000).
Ctrl+C encerra de forma limpa (drena a fila, fecha o log, grava o extrato).

### Arquivos gerados (pasta `data/`, ignorada pelo git)

| Arquivo | Conteúdo |
|---|---|
| `data/telemetria.mv.db` | banco H2 com a tabela `alerta` (persiste entre execuções) |
| `data/leituras.log` | todas as leituras, uma por linha, `;` como separador, UTF-8 |
| `data/extrato-AAAAMMDD-HHmmss.txt` | resumo de cada execução (gerado ao parar) |

## Estrutura

```
src/main/        código (pacotes sem prefixo: domain, infra, app)
├── domain/      Sensor (Runnable), LeituraTelemetria, Alerta, AnalisadorDeAnomalias, RegraDeAnomalia,
│                DegrauDeSeveridade, GeradorPasseioAleatorio, portas PublicadorDeLeituras e RepositorioDeAlertas
├── infra/       FilaDeLeituras, AlertaRepository (JDBC), LogArquivo (I/O), FabricaDeConexoes, InicializadorBanco
└── app/         ControladorTelemetria, ProcessadorDeLeituras (consumidor), ContadoresExecucao, OuvinteConsole,
    │            FormatoMensagens, Main
    └── ui/      tema/ (Tema, Icones, Componentes, MensagemErro) · telas/ · demo/ (ControladorSimulado)
src/test/        testes JUnit, nos mesmos pacotes (+ estresse/)
src/resources/   db.properties · schema.sql · consultas.sql
distribuicao/    programa pronto (Telemetria.exe, telemetria.jar, lib/)
lancador/        código do .exe (Go) e ícone
entregaveis/     relatório, script SQL, log e extrato de exemplo
docs/            prévias das telas e resultados do estresse
```

As pastas fogem do padrão `src/main/java` do Maven para evitar níveis vazios; o `pom.xml`
indica onde está cada uma (`sourceDirectory`, `testSourceDirectory`, `resources`).

`domain/` não importa banco, arquivo nem `infra`: o `Sensor` publica por `PublicadorDeLeituras`
(implementado pela `FilaDeLeituras`) e o consumidor grava por `RepositorioDeAlertas`
(implementado pelo `AlertaRepository`). A interface só conhece `ControladorMonitoramento` e
`OuvinteTelemetria`.

## Threads

| Thread | Quem | O que faz |
|---|---|---|
| `sensor-<ID>` | `Sensor` | gera leituras e faz `put()` na fila (espera se cheia) |
| `consumidor-<n>` | `ProcessadorDeLeituras` | `take()` → analisa → grava alerta → log → tela |
| `log-escritor` | `LogArquivo` | única thread que escreve no arquivo |
| `estatisticas` | `ScheduledExecutorService` | publica contadores a cada 1 s |
| EDT / `SwingWorker` | interface | tela; consulta ao banco, parar e exportar em segundo plano |

Encerramento: interrompe e espera os sensores → uma pílula de fim por consumidor → consumidores
drenam a fila e saem → desliga estatísticas → fecha o log → grava o extrato.

## Banco de dados

- Configuração em `src/resources/db.properties` (qualquer chave pode ser sobrescrita com
  `-Ddb.url=...`). Padrão: H2 em arquivo, `jdbc:h2:./data/telemetria;DB_CLOSE_DELAY=-1`.
- `schema.sql` cria a tabela `alerta` com `CREATE TABLE IF NOT EXISTS` (roda a cada início, não apaga dados).
- `consultas.sql` tem consultas para conferir os alertas depois de encerrar o programa.
- Todo o SQL fica no `AlertaRepository`, sempre com `PreparedStatement` e try-with-resources;
  cada operação usa a sua própria conexão (`Connection` não é compartilhada entre threads).
- PostgreSQL: adicione `org.postgresql:postgresql` no `pom.xml` e troque `db.url`/`db.usuario`/`db.senha`.

## Interface

Padrões visuais na skill `.claude/skills/designer-swing-telemetria/` e prévias em `docs/telas/`.
Abas: **Monitoramento** (cartões dos sensores, leituras ao vivo, alertas recentes),
**Histórico de alertas** (consulta ao banco com filtros, inclui execuções anteriores) e
**Regras e sensores** (faixas e escala de severidade; configuração da simulação com o sistema parado).

## Revisão automática

```bash
bash .claude/skills/revisor-telemetria/verificar.sh .
```
