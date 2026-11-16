# Relatório — Sistema de Recepção e Análise de Telemetria

POO (Java) · 2º bimestre · Opção B · UniCesumar 2026

## 1. Arquitetura

O código está separado em três pacotes, em `src/main/`:

| Pacote | Responsabilidade | Classes principais |
|---|---|---|
| `domain` | Regras de negócio e entidades, sem nenhuma dependência de banco, arquivo ou threads de infraestrutura | `Sensor` (Runnable), `LeituraTelemetria`, `Alerta`, `AnalisadorDeAnomalias`, `RegraDeAnomalia`, `ConfiguracaoSensores` (sensores e a faixa de cada um), `GeradorPorEventosDiscretos` (seção 3), portas `PublicadorDeLeituras`, `RepositorioDeAlertas` e `RepositorioDeConfiguracao` |
| `infra` | Detalhes técnicos: fila, banco e arquivo | `FilaDeLeituras` (thread-safe), `AlertaRepository` e `ConfiguracaoRepository` (JDBC), `LogArquivo` (I/O) |
| `app` | Orquestração e interface | `ControladorTelemetria`, `ProcessadorDeLeituras` (consumidor), `Main`, telas Swing em `app/ui` |

O domínio define **interfaces (portas)** que a infraestrutura implementa: o `Sensor` só conhece
`PublicadorDeLeituras` (implementado pela `FilaDeLeituras`) e o consumidor grava alertas por
`RepositorioDeAlertas` (implementado pelo `AlertaRepository`); a configuração dos sensores é lida e
gravada por `RepositorioDeConfiguracao` (implementado pelo `ConfiguracaoRepository`). Assim `domain/` não importa
`java.sql`, `java.io` nem `infra`.

Fluxo de uma leitura:

```
Sensor (thread sensor-TEMP-01) ──put──► FilaDeLeituras ──take──► ProcessadorDeLeituras (thread consumidor-1)
                                         (capacidade 1000)          │ AnalisadorDeAnomalias.analisar()
                                                                    ├─► AlertaRepository.salvar()  (se anômala)
                                                                    ├─► LogArquivo.registrar()     (todas)
                                                                    └─► interface / console
```

## 2. Regras de anomalia

Cada **sensor** tem a sua **faixa aceita** (limites inclusivos: um valor exatamente no limite é
normal), configurada na aba **Configurações** e gravada no banco. Cada tipo tem uma **faixa
padrão**, que só decide como os sensores *novos* nascem — mudar o padrão não altera os sensores que
já existem. Fora da faixa, a **severidade** depende de quanto a leitura passou do limite, medido
como fração da largura da faixa **do sensor**: até 5 % BAIXA · até 15 % MEDIA · até 30 % ALTA ·
acima disso CRITICA. Configuração inicial: 4 sensores (TEMP-01, VEL-01, PRES-01, UMID-01), 200 ms
entre leituras e as faixas padrão abaixo.

| Tipo | Faixa padrão | BAIXA | MEDIA | ALTA | CRITICA |
|---|---|---|---|---|---|
| Temperatura | 0 – 80 °C | até 4,0 | até 12,0 | até 24,0 | > 24,0 |
| Velocidade | 0 – 120 km/h | até 6,0 | até 18,0 | até 36,0 | > 36,0 |
| Pressão | 1 – 8 bar | até 0,35 | até 1,05 | até 2,1 | > 2,1 |
| Umidade | 20 – 90 % | até 3,5 | até 10,5 | até 21,0 | > 21,0 |

(valores = quanto passou do limite, para cima ou para baixo, na unidade do sensor; com a faixa
padrão)

Exemplo: 92,5 °C passou 12,5 do limite de 80 → 15,6 % da largura → **ALTA**, regra
"acima do limite 80,0 °C". Um sensor TEMP-02 com faixa própria de 10–60 °C dá alerta já em
62,4 °C ("acima do limite 60,0 °C") e seus degraus são calculados sobre a largura 50.
As faixas padrão existem num único lugar (`RegraDeAnomalia`) e os limiares de severidade em outro
(`AnalisadorDeAnomalias`). O analisador é uma regra **pura**: recebe a leitura, escolhe a faixa
pelo id do sensor (sensor desconhecido usa o padrão do tipo) e devolve `Optional<Alerta>`, sem
efeitos colaterais. Foi escrito em **TDD** (testes primeiro, vistos falhando, depois a
implementação): `AnalisadorDeAnomaliasTest` tem 53 casos, todos sem threads e sem banco, cobrindo
cada tipo acima/abaixo da faixa, exatamente no limite, cada fronteira de severidade e a faixa por
sensor (o mesmo valor é normal num sensor e alerta em outro do mesmo tipo).
`ConfiguracaoSensoresTest` (29 casos) cobre ids, padrão × faixa própria, validações e imutabilidade.

## 3. Geração dos valores — simulação de eventos discretos

Os sensores são simulados. Cada sensor tem um **relógio simulado** (1 passo = 1 leitura = 1
intervalo do sensor) e uma **agenda de eventos** (`PriorityQueue` ordenada pelo passo). Quatro
eventos levam o sensor por um episódio de anomalia:

```
NORMAL ─INICIO_AQUECIMENTO─► AQUECENDO ─INICIO_EPISODIO─► CRISE ─FIM_EPISODIO─► ESFRIANDO ─FIM_ESFRIAMENTO─► NORMAL
```

- **INICIO_AQUECIMENTO** planeja o episódio: severidade-alvo (pesos BAIXA 40 · MEDIA 30 · ALTA 20 ·
  CRITICA 10), direção (velocidade só acima; temperatura 80 % acima; pressão e umidade 50/50), se
  passa do limite (80 % dos episódios) e a duração de cada fase (uniformes: aquecimento e
  esfriamento 6 a 12 leituras, crise 6 a 14). Cada evento agenda o seguinte.
- **FIM_ESFRIAMENTO** agenda o próximo episódio depois de um tempo normal **exponencial** de média
  50 leituras (processo de Poisson; ~10 s a 200 ms — um episódio a cada ~16 s por sensor).
- **Valores** (sempre mirando a faixa **do próprio sensor**): no normal, passeio aleatório dentro da faixa. **Aquecendo**, o valor sobe em linha
  reta até logo antes do limite. Na **crise** ele cruza o limite, cresce até o pico (no miolo do
  degrau da severidade-alvo), fica nele e desce de volta. **Esfriando**, volta ao nível normal.
  Nos 20 % de episódios que não passam do limite (quase-incidentes) o sensor aquece, fica logo
  antes do limite e esfria, sem alerta. Resultado: alertas só em intervalos (10 seguidos em média,
  contra 1,05 antes), ~10 % das leituras, sem saltos bruscos (99 % das variações entre leituras
  seguidas ficam abaixo de 13 % da largura da faixa; antes os picos saltavam mais de 40 %).

Classes (todas em `domain`, só `java.util`): `EventoDeSimulacao`, `EpisodioDeAnomalia`,
`AgendaDeEventos`, `ParametrosDeEpisodios`, `CicloDeEpisodios` (decide **quando**) e
`GeradorPorEventosDiscretos` (decide **quanto**; implementa a mesma interface `GeradorDeValores`,
então o `Sensor` não mudou). O gerador **não decide se é alerta** — as faixas e degraus vêm do
`AnalisadorDeAnomalias`, que continua sendo a única autoridade; os testes o usam como oráculo
(fora da crise nunca há alerta; o pico da crise é a severidade-alvo). Cada gerador pertence a um
sensor e só é chamado pela thread dele (**confinamento de thread**), por isso a `PriorityQueue`
não precisa de lock; ao religar um sensor, o `join()` da thread antiga antes do `start()` da nova
garante que ela enxergue o estado deixado. No console, `--episodio-medio SEGUNDOS` muda o tempo
normal médio entre episódios.

## 4. Como a concorrência foi tratada

**Threads.** Cada sensor roda na sua thread (`sensor-<ID>`), os consumidores em `consumidor-<n>`,
uma thread `log-escritor` grava o arquivo e um `ScheduledExecutorService` publica estatísticas
a cada 1 s.

**Fila bloqueante.** A `FilaDeLeituras` envolve um `ArrayBlockingQueue` e é o **único** ponto de
contato entre sensores e consumidores. `put()` faz o sensor **esperar** quando a fila está cheia
(nada é descartado, ao contrário de `offer()`); `take()` faz o consumidor esperar quando está
vazia; cada elemento sai exatamente uma vez, mesmo com vários consumidores.

**Encerramento com pílula de veneno.** `parar()`: (1) interrompe os sensores e espera cada um com
`join`; (2) coloca uma "pílula" por consumidor **atrás** de todas as leituras; (3) cada consumidor
processa tudo o que estava na fila até achar a sua pílula e termina; (4) desliga as estatísticas
(`shutdown` + `awaitTermination`) e fecha o log. Nenhuma leitura fica para trás. A sequência de
cada sensor só avança depois que o `put()` retorna, então uma leitura interrompida não deixa buraco.

**Configuração imutável.** `ConfiguracaoSensores` é um record imutável: toda alteração cria uma
nova. O controlador só a troca com o sistema **parado** (sob o seu lock) e cada execução cria um
`AnalisadorDeAnomalias` com ela — também imutável, compartilhado pelos consumidores sem lock. Assim
as faixas nunca mudam no meio de uma execução e nenhuma thread de trabalho ganhou lock novo.

**Log com escritor único.** Os consumidores só enfileiram a linha numa fila interna do `LogArquivo`;
uma única thread escreve com `BufferedWriter` (UTF-8, try-with-resources). As linhas nunca se
misturam e nenhum consumidor espera o disco.

**Uma conexão JDBC por operação.** `Connection` não é thread-safe, então nenhuma é compartilhada:
cada `salvar` abre, usa e fecha a sua (try-with-resources em `Connection`, `PreparedStatement` e
`ResultSet`). Falha de banco não derruba o consumidor: o alerta conta como "não salvo" e o usuário
é avisado uma vez.

**Por que não há deadlock.** Nenhuma thread segura um lock enquanto pede outro: o lock da fila só
é segurado na inserção/remoção (e liberado durante a espera), o monitor de pausa de cada sensor
nunca é segurado durante o `put`, e nenhum I/O ou SQL acontece dentro de lock. Sem locks aninhados
não existe o ciclo de espera que caracteriza o deadlock. Todo `join` tem tempo limite.

## 5. Persistência

H2 em **arquivo** (`./data/telemetria.mv.db`), configurado em `db.properties`. O script
`schema.sql` usa `CREATE TABLE IF NOT EXISTS` (não apaga dados) e cria quatro tabelas: `alerta` e
as da configuração — `config_geral` (intervalo), `config_tipo` (faixa padrão de cada tipo) e
`config_sensor` (faixa e ordem de cada sensor), com `CHECK (minimo < maximo)`. O
`ConfiguracaoRepository` grava a configuração inteira **numa transação** (rollback em qualquer
erro: nunca fica pela metade). Prova (`prova-persistencia.txt`, três processos separados): a
execução 1 gravou 5 alertas; outro processo adicionou TEMP-02 (10–60 °C), mudou o padrão de pressão
e o intervalo para 300 ms; a execução 2, aberta **sem nenhuma opção**, rodou com 5 sensores a
300 ms e TEMP-02 na faixa própria, e a tabela `alerta` terminou com 17 linhas (ids #6 em diante).
A aba Histórico mostra alertas de execuções anteriores.

## 6. Resultados do teste de estresse

`TesteDeEstresse` roda o sistema real com fila de capacidade 50 e 4 consumidores, 5 rodadas de 5 s
por cenário, e confere: produzidas == processadas, cada (sensor, sequência) processado exatamente
uma vez e sem buraco, uma linha de log por leitura, alertas no banco == alertas gerados, e tudo
dentro do tempo limite (sem deadlock).

| Cenário | Leituras/s | Perdidas | Fila cheia → sensor esperou |
|---|---|---|---|
| 32 sensores · 10 ms | ~3.155 | 0 em todas | 0 |
| 40 sensores · 10 ms | ~3.935 | 0 em todas | 0 |
| 40 sensores · banco lento (+30 ms/alerta) | ~1.310 | 0 em todas | 4.800 a 5.600 vezes por rodada |
| 11 sensores · 8 de temperatura com faixas diferentes | ~1.085 | 0 em todas | 0 |

(Linux, 15/11/2026, 20 de 20 rodadas aprovadas, com a faixa por sensor e a configuração lida do
banco. No cenário de faixas diferentes, cada um dos 2.094 alertas de temperatura gravados foi
conferido contra a faixa do **próprio** sensor. No banco lento a vazão é limitada pelo banco — 4
consumidores ÷ 30 ms ≈ 133 alertas/s com ~10 % de leituras virando alerta.)

Problema encontrado no estresse e corrigido: o H2 em arquivo fechava o banco a cada operação
(última conexão fechada) e reabria o arquivo no alerta seguinte, derrubando a vazão para ~1.000
leituras/s. A URL passou a usar `DB_CLOSE_DELAY=-1`. Execução pelo console com 40 sensores por
20 s: 78.646 leituras, 0 perdidas, 8.562 alertas, todos gravados. Detalhes em `docs/estresse.md`.

## 7. Como rodar

Executável: copie a pasta `distribuicao/` para qualquer máquina com Java 17+ e abra
`Telemetria.exe` (o H2 vai junto; nada mais precisa ser baixado).

Para desenvolver: JDK 17+ e Maven 3.9+.

```
mvn test                                               # 278 testes (unidade + integração)
mvn test -Dgroups=estresse                             # 20 rodadas de estresse (~2 min)
mvn -q compile exec:java                               # interface gráfica
mvn -q compile exec:java "-Dexec.args=--console 15"    # sem janela, 15 s
mvn -q compile exec:java "-Dexec.args=--console 30 --episodio-medio 5"   # episódios mais frequentes
mvn -q compile exec:java "-Dexec.args=--console 15 --sensores 3 --intervalo 50"  # só nesta execução
```

O modo console usa a configuração gravada (a mesma da aba Configurações); `--sensores N` e
`--intervalo MS` valem só para aquela execução e não são gravados.

Arquivos desta pasta: `schema.sql` (criação da tabela), `leituras-exemplo.log` e
`extrato-exemplo.txt` (execução de 30 s com a configuração padrão — 4 sensores, um por tipo,
200 ms: 600 leituras, 0 perdidas, 80 alertas em 8 crises; refeita em 15/11 com o extrato que
lista a faixa de cada sensor), `console-exemplo.txt` (saída do console dessa execução) e `prova-persistencia.txt`.
