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

Rodado de novo em 10/11/2026 com a **simulação de eventos discretos** completa
(`GeradorPorEventosDiscretos`: cada episódio aquece, entra em crise e esfria; ~10 % das leituras
viram alerta, em rajadas, em vez de ~5 % em picos isolados). Linux, JDK 21 compilando para Java 17.

### 32 sensores

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 15.829 | 15.829 | 0 | 1.627 (1.627) | 0 | 3.165 |
| 2/5 | 15.827 | 15.827 | 0 | 1.605 (1.605) | 0 | 3.165 |
| 3/5 | 15.822 | 15.822 | 0 | 1.499 (1.499) | 0 | 3.164 |
| 4/5 | 15.836 | 15.836 | 0 | 1.686 (1.686) | 0 | 3.167 |
| 5/5 | 15.852 | 15.852 | 0 | 1.614 (1.614) | 0 | 3.170 |

### 40 sensores (carga máxima)

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 19.710 | 19.710 | 0 | 2.124 (2.124) | 0 | 3.942 |
| 2/5 | 19.794 | 19.794 | 0 | 2.108 (2.108) | 0 | 3.958 |
| 3/5 | 19.775 | 19.775 | 0 | 1.836 (1.836) | 0 | 3.955 |
| 4/5 | 19.763 | 19.763 | 0 | 1.961 (1.961) | 0 | 3.952 |
| 5/5 | 19.822 | 19.822 | 0 | 1.865 (1.865) | 0 | 3.964 |

### Banco lento — fila cheia, sensores esperando

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 7.593 | 7.593 | 0 | 635 (635) | 5.579 | 1.518 |
| 2/5 | 6.713 | 6.713 | 0 | 656 (656) | 5.213 | 1.342 |
| 3/5 | 6.362 | 6.362 | 0 | 661 (661) | 4.723 | 1.272 |
| 4/5 | 6.547 | 6.547 | 0 | 651 (651) | 5.338 | 1.309 |
| 5/5 | 7.388 | 7.388 | 0 | 651 (651) | 5.395 | 1.477 |

"Esperas por fila cheia" = quantas vezes um sensor chegou com a fila cheia e ficou bloqueado no
`put()` até um consumidor abrir espaço. Mesmo com milhares de esperas, **nenhuma leitura foi
perdida**: a vazão cai (o sistema se adapta ao consumidor mais lento), mas nada é descartado.

**Por que o banco lento ficou mais lento que antes (~1.380 contra ~2.600 leituras/s)?** Não é
regressão: com 4 consumidores e 30 ms por alerta, o banco aguenta no máximo 4 / 0,030 ≈ 133
alertas/s. Com ~5 % de alertas (gerador antigo) isso limita a vazão a 133 / 0,05 ≈ 2.670
leituras/s; com ~10 % (gerador novo), a 133 / 0,10 ≈ 1.330 — o que foi medido (1.270 a 1.520).
Os cenários sem atraso (limitados pelos sensores, não pelo banco) não mudaram.

### Histórico — gerador anterior (picos isolados, ~5 % de alertas)

| Cenário | Linux (26/10) | Windows (`mvn test -Dgroups=estresse`) | Perdidas | Esperas por fila cheia |
|---|---|---|---|---|
| 32 sensores | 3.146 – 3.154 leituras/s | 3.010 – 3.017 | 0 em todas | 0 |
| 40 sensores | 3.890 – 3.940 | 3.722 – 3.768 | 0 em todas | 0 |
| Banco lento | 2.517 – 2.737 | 2.380 – 2.595 | 0 em todas | 5.039 – 6.204 |

A reprodução no Windows ainda não foi refeita com o gerador novo: rode `mvn test -Dgroups=estresse`
no computador de desenvolvimento e acrescente os números aqui.

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

Trecho da saída (10/11/2026, gerador por eventos discretos com aquecimento e esfriamento; a saída
completa, com as 8.562 linhas ALERTA, está em `docs/console-estresse-20s.txt`):

```
17:12:40.498  INFO    [banco]       Conectado: jdbc:h2:./data/telemetria;DB_CLOSE_DELAY=-1
17:12:40.559  INFO    [sistema]     40 sensores e 4 consumidor(es) iniciados (intervalo 10 ms)
17:12:40.569  INFO    [sistema]     Simulação por eventos discretos: ~0,5 s normais (50 leituras) em média entre episódios; cada episódio aquece 0,06 a 0,12 s, fica em crise 0,06 a 0,14 s (passa do limite em 80 % deles) e esfria 0,06 a 0,12 s
17:12:40.736  ALERTA  [PRES-09]     8,9 bar: acima do limite 8,0 bar | MEDIA | salvo #4
17:12:40.722  ALERTA  [UMID-09]     91,4 %: acima do limite 90,0 % | BAIXA | salvo #2
17:12:40.732  ALERTA  [UMID-09]     92,7 %: acima do limite 90,0 % | BAIXA | salvo #3
17:12:40.724  ALERTA  [PRES-09]     8,4 bar: acima do limite 8,0 bar | MEDIA | salvo #1
...
17:13:00.584  ALERTA  [UMID-09]     90,5 %: acima do limite 90,0 % | BAIXA | salvo #8558
17:13:00.584  ALERTA  [VEL-09]      122,8 km/h: acima do limite 120,0 km/h | BAIXA | salvo #8559
17:13:00.587  ALERTA  [PRES-06]     0,8 bar: abaixo do limite 1,0 bar | BAIXA | salvo #8560
17:13:00.588  ALERTA  [UMID-10]     12,1 %: abaixo do limite 20,0 % | MEDIA | salvo #8561
17:13:00.590  ALERTA  [TEMP-02]     89,9 °C: acima do limite 80,0 °C | MEDIA | salvo #8562
17:13:00.609  INFO    [sistema]     Encerrado: 78.646 leituras, 0 perdidas, 8.562 alertas. Extrato: data/extrato-20261110-171300.txt

EXTRATO DA EXECUÇÃO — Central de Telemetria
Período ......... 10/11/2026 17:12:40 → 17:13:00 (0:00:20)
Configuração .... 40 sensores · 4 consumidor(es) · intervalo 10 ms

LEITURAS
  Produzidas ...... 78.646
  Processadas ..... 78.646
  Perdidas ........ 0   (produzidas − processadas)

ALERTAS POR TIPO / SEVERIDADE
  TEMPERATURA   BAIXA 1.143 MEDIA 626  ALTA 257  CRITICA 70    total 2.096
  VELOCIDADE    BAIXA 1.004 MEDIA 706  ALTA 296  CRITICA 83    total 2.089
  PRESSAO       BAIXA 1.176 MEDIA 672  ALTA 301  CRITICA 47    total 2.196
  UMIDADE       BAIXA 1.203 MEDIA 570  ALTA 325  CRITICA 83    total 2.181
  TOTAL ........... 8.562 alertas  (gravados no banco: 8.562)

ARQUIVOS
  Log de leituras  data/leituras.log
  Banco            jdbc:h2:./data/telemetria;DB_CLOSE_DELAY=-1
```

78.646 leituras em 20 s, **0 perdidas**, 8.562 alertas, **todos gravados no banco**; o log ficou
com 78.647 linhas (cabeçalho + uma por leitura). Nenhuma linha AVISO ou ERRO. Os alertas de um
mesmo sensor vêm em sequência, subindo e descendo. Por alerta, as severidades ficam BAIXA 53 % ·
MEDIA 30 % · ALTA 14 % · CRITICA 3 % — mais baixas que os pesos por episódio (40/30/20/10)
porque a subida e a descida de cada crise passam pelas severidades menores antes e depois do
pico. Nenhuma velocidade ficou abaixo de 0 km/h — o gerador anterior produzia valores como
−45,0 km/h. (Com mais de 999 alertas numa célula, a coluna do extrato perde o alinhamento,
como em "BAIXA 1.143 MEDIA" — só aparência; os números estão certos.)
