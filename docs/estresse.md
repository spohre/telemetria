# Concorrência sob estresse — Semana 5

Teste automatizado: `src/test/estresse/TesteDeEstresse.java`
(`@Tag("estresse")`, fora do `mvn test` padrão).

```
mvn test -Dgroups=estresse
```

## O que é testado

O **sistema real** — `ControladorTelemetria` com sensores em threads, `FilaDeLeituras`, 4
consumidores (`ProcessadorDeLeituras`), `LogArquivo` e H2 em arquivo — encerrado pelo mesmo
`parar()` que a interface usa. Cada rodada roda 5 s e confere:

1. **zero perdidas:** leituras produzidas == processadas;
2. **sem duplicação e sem buraco:** cada par (sensor, sequência) é processado exatamente uma vez
   e, por sensor, as sequências vão de 1 até a última sem faltar nenhuma (verificado por um
   ouvinte que recebe cada leitura processada com a sua `sequencia`);
3. **log completo:** linhas em `data/leituras.log` == processadas + 1 (cabeçalho), e as linhas
   ANOMALA == alertas gerados;
4. **banco completo:** alertas gerados == alertas gravados == linhas na tabela `alerta`;
5. **sem deadlock:** tudo dentro de `assertTimeoutPreemptively(60 s)` — se algo travar, o teste falha.

## Configuração

| Cenário | Sensores | Intervalo | Consumidores | Fila | Banco |
|---|---|---|---|---|---|
| 32 sensores | 8 por tipo × 4 tipos | 10 ms | 4 | 50 | H2 em arquivo |
| 40 sensores (carga máxima) | 10 por tipo × 4 tipos | 10 ms | 4 | 50 | H2 em arquivo |
| Banco lento | 10 por tipo × 4 tipos | 10 ms | 4 | 50 | H2 em arquivo **+ 30 ms por gravação** |

- **Por que não 30 sensores a 5 ms (como no plano)?** A `ConfiguracaoSimulacao` aceita intervalo
  de 10 a 5000 ms e sensores em múltiplos de 4 (um conjunto por tipo) — regra da interface. Em vez
  de contorná-la ou montar o sistema à mão só no teste, usamos o sistema real com a carga mais
  alta que ele aceita (32 e 40 sensores a 10 ms = ~3.200 e ~4.000 leituras/s).
- **Por que "banco lento"?** Com o banco rápido os 4 consumidores dão conta e a fila não chega a
  encher. O atraso artificial de 30 ms por alerta (sobre o H2 real) simula um servidor de banco
  distante: os consumidores ficam para trás, a fila de 50 enche e os sensores **precisam
  esperar** — é o cenário que prova a contrapressão sem perda.

## Resultados (5 rodadas por cenário, todas aprovadas)

### 32 sensores

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 15.774 | 15.774 | 0 | 767 (767) | 0 | 3.154 |
| 2/5 | 15.734 | 15.734 | 0 | 697 (697) | 0 | 3.146 |
| 3/5 | 15.743 | 15.743 | 0 | 791 (791) | 0 | 3.148 |
| 4/5 | 15.761 | 15.761 | 0 | 810 (810) | 0 | 3.152 |
| 5/5 | 15.742 | 15.742 | 0 | 793 (793) | 0 | 3.148 |

### 40 sensores (carga máxima)

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 19.473 | 19.473 | 0 | 1.005 (1.005) | 0 | 3.894 |
| 2/5 | 19.477 | 19.477 | 0 | 960 (960) | 0 | 3.895 |
| 3/5 | 19.476 | 19.476 | 0 | 938 (938) | 0 | 3.895 |
| 4/5 | 19.454 | 19.454 | 0 | 984 (984) | 0 | 3.890 |
| 5/5 | 19.704 | 19.704 | 0 | 996 (996) | 0 | 3.940 |

### Banco lento — fila cheia, sensores esperando

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 13.688 | 13.688 | 0 | 603 (603) | 5.039 | 2.737 |
| 2/5 | 12.933 | 12.933 | 0 | 647 (647) | 6.204 | 2.586 |
| 3/5 | 12.586 | 12.586 | 0 | 640 (640) | 5.630 | 2.517 |
| 4/5 | 12.675 | 12.675 | 0 | 647 (647) | 5.865 | 2.535 |
| 5/5 | 13.494 | 13.494 | 0 | 624 (624) | 5.277 | 2.698 |

"Esperas por fila cheia" = quantas vezes um sensor chegou com a fila cheia e ficou bloqueado no
`put()` até um consumidor abrir espaço. Mesmo com milhares de esperas, **nenhuma leitura foi
perdida**: a vazão cai (o sistema se adapta ao consumidor mais lento), mas nada é descartado.

### Reprodução em outra máquina (Windows, `mvn test -Dgroups=estresse`)

As mesmas 15 rodadas, no computador de desenvolvimento (Windows), também passaram todas:

| Cenário | Leituras/s por rodada | Perdidas | Esperas por fila cheia |
|---|---|---|---|
| 32 sensores | 3.010 – 3.017 | 0 em todas | 0 |
| 40 sensores | 3.722 – 3.768 | 0 em todas | 0 |
| Banco lento | 2.380 – 2.595 | 0 em todas | 5.355 – 5.978 |

Os números acima (Linux) e estes (Windows) batem: o comportamento não depende da máquina.

## Problema encontrado e corrigido

Na primeira execução todas as rodadas passaram, mas a vazão ficou instável e baixa (400 a 1.800
leituras/s, contra ~4.000 possíveis) e a fila enchia mesmo com banco "rápido".

**Causa:** o `AlertaRepository` abre e fecha uma conexão por operação (correto: `Connection` não é
thread-safe e não é compartilhada). Mas o H2 em arquivo **fecha o banco inteiro quando a última
conexão é fechada** — então cada alerta reabria o arquivo do banco.

**Correção (no sistema, não no teste):** `db.properties` passou a usar
`jdbc:h2:./data/telemetria;DB_CLOSE_DELAY=-1`, que mantém o banco aberto até a JVM terminar.
Resultado: vazão estável no máximo teórico (~3.150 e ~3.900 leituras/s).

## Execução pesada pelo console

```
mvn -q compile exec:java "-Dexec.args=--console 20 --sensores 10 --intervalo 10 --consumidores 4 --fila 50"
```

Trecho da saída (a saída completa, com as 3.850 linhas ALERTA, está em `docs/console-estresse-20s.txt`):

```
11:20:08.172  INFO    [banco]       Conectado: jdbc:h2:./data/telemetria;DB_CLOSE_DELAY=-1
11:20:08.201  INFO    [sistema]     40 sensores e 4 consumidor(es) iniciados (intervalo 10 ms)
11:20:08.222  ALERTA  [TEMP-03]     83,9 °C: acima do limite 80,0 °C | BAIXA | salvo #3
11:20:08.219  ALERTA  [PRES-07]     0,4 bar: abaixo do limite 1,0 bar | MEDIA | salvo #4
11:20:08.226  ALERTA  [VEL-01]      -45,0 km/h: abaixo do limite 0,0 km/h | CRITICA | salvo #5
11:20:08.214  ALERTA  [TEMP-06]     -25,4 °C: abaixo do limite 0,0 °C | CRITICA | salvo #1
...
11:20:28.186  ALERTA  [TEMP-05]     -31,5 °C: abaixo do limite 0,0 °C | CRITICA | salvo #3845
11:20:28.196  ALERTA  [PRES-02]     0,4 bar: abaixo do limite 1,0 bar | MEDIA | salvo #3846
11:20:28.196  ALERTA  [VEL-03]      145,5 km/h: acima do limite 120,0 km/h | ALTA | salvo #3847
11:20:28.206  ALERTA  [VEL-03]      125,7 km/h: acima do limite 120,0 km/h | BAIXA | salvo #3848
11:20:28.216  ALERTA  [TEMP-07]     -4,8 °C: abaixo do limite 0,0 °C | MEDIA | salvo #3849
11:20:28.227  ALERTA  [UMID-09]     -7,4 %: abaixo do limite 20,0 % | CRITICA | salvo #3850
11:20:28.257  INFO    [sistema]     Encerrado: 77.998 leituras, 0 perdidas, 3.850 alertas. Extrato: data/extrato-20261026-112028.txt

EXTRATO DA EXECUÇÃO — Central de Telemetria
Período ......... 26/10/2026 11:20:08 → 11:20:28 (0:00:20)
Configuração .... 40 sensores · 4 consumidor(es) · intervalo 10 ms

LEITURAS
  Produzidas ...... 77.998
  Processadas ..... 77.998
  Perdidas ........ 0   (produzidas − processadas)

ALERTAS POR TIPO / SEVERIDADE
  TEMPERATURA   BAIXA 77   MEDIA 221  ALTA 378  CRITICA 235   total 911
  VELOCIDADE    BAIXA 64   MEDIA 285  ALTA 378  CRITICA 247   total 974
  PRESSAO       BAIXA 72   MEDIA 254  ALTA 388  CRITICA 253   total 967
  UMIDADE       BAIXA 77   MEDIA 282  ALTA 379  CRITICA 260   total 998
  TOTAL ........... 3.850 alertas  (gravados no banco: 3.850)

ARQUIVOS
  Log de leituras  data/leituras.log
  Banco            jdbc:h2:./data/telemetria;DB_CLOSE_DELAY=-1
```

77.998 leituras em 20 s, **0 perdidas**, 3.850 alertas, **todos gravados no banco**; o log ficou
com 77.999 linhas (cabeçalho + uma por leitura). Nenhuma linha AVISO ou ERRO.

## Por que não há deadlock

Deadlock exige um ciclo: uma thread segura um lock e espera outro que está com uma segunda
thread, que por sua vez espera o primeiro. O sistema foi desenhado para que esse ciclo seja
impossível — **nenhuma thread segura um lock enquanto pede outro**:

| Lock | Quem usa | Quanto tempo fica segurado |
|---|---|---|
| Lock interno do `ArrayBlockingQueue` (`FilaDeLeituras`) | sensores e consumidores | só durante a inserção/remoção. `put()` e `take()` esperam em condições ("não cheia"/"não vazia") que **liberam** o lock enquanto esperam |
| `monitorPausa` de cada `Sensor` (um por sensor) | thread do sensor e quem chama pausar/retomar | só para ler/mudar o flag ou no `wait()` (que libera o monitor). **Nunca** segurado durante o `publicar` |
| Filas internas do `LogArquivo` | consumidores (enfileiram) e a thread `log-escritor` | só para enfileirar/desenfileirar. A escrita em disco acontece **fora** de qualquer lock, numa thread só |
| `synchronized` do `ControladorTelemetria` | só a thread da interface/main (iniciar, pausar, parar) | durante o encerramento. Sensores, consumidores e estatísticas **nunca** pedem esse lock, então segurá-lo durante o `join()` não fecha ciclo |
| Conexão JDBC | cada operação abre a sua | nenhum lock da aplicação é segurado durante SQL |

Além disso:

- **Um lock por objeto, nenhum aninhado:** não existe trecho que segure dois locks ao mesmo tempo,
  então não há ordem de aquisição a respeitar.
- **Nenhum I/O ou SQL dentro de lock:** o consumidor retira a leitura (lock da fila liberado) e só
  então analisa, grava no banco e registra no log.
- **`put`/`take` bloqueantes:** produtor e consumidor esperam pelo outro sem *busy-wait* e sem
  `offer()` que descartaria leituras.
- **Pílula de veneno no encerramento:** os sensores são interrompidos e aguardados (`join`) primeiro;
  depois entra uma pílula por consumidor **atrás** de todas as leituras. Cada consumidor drena a fila
  até a sua pílula e termina — a fila é esvaziada antes de o último sair, sem perder leituras e sem
  depender de interromper consumidores no meio do processamento.
- **Tempo limite em todo `join()`:** se, por um erro futuro, alguma thread não terminar, o `parar()`
  falha com mensagem clara ("A thread X não terminou dentro do tempo limite") em vez de travar.

## Por que nenhuma leitura se perde

- `publicar` usa `put()` — com a fila cheia o sensor espera; nada é descartado.
- A `sequencia` do sensor só avança depois que o `publicar` retorna: uma leitura interrompida no meio
  não conta e não deixa buraco.
- O consumidor conta a leitura como processada mesmo se o log ou a tela falharem (`finally`), e falha
  de banco vira "alerta não salvo" sem derrubar a thread.
- `parar()` só monta o extrato depois de os consumidores drenarem tudo e de o log gravar o que estava
  pendente (`LogArquivo.fechar()`).
