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

A configuração dos sensores de cada cenário é **gravada no banco** (`ConfiguracaoRepository`) antes
de abrir o controlador, exatamente como a aba Configurações faz — o controlador a lê de lá.

## Configuração

| Cenário | Sensores | Intervalo | Consumidores | Fila | Banco |
|---|---|---|---|---|---|
| 32 sensores | 8 por tipo × 4 tipos | 10 ms | 4 | 50 | H2 em arquivo |
| 40 sensores (carga máxima) | 10 por tipo × 4 tipos | 10 ms | 4 | 50 | H2 em arquivo |
| Banco lento | 10 por tipo × 4 tipos | 10 ms | 4 | 50 | H2 em arquivo **+ 30 ms por gravação** |
| Faixas diferentes no mesmo tipo | 8 de temperatura (TEMP-0k aceita 5k a 40+5k °C) + 1 de cada outro tipo | 10 ms | 4 | 50 | H2 em arquivo |

- **Por que não 30 sensores a 5 ms (como no plano)?** A configuração dos sensores
  (`ConfiguracaoSensores`) aceita intervalo de 10 a 5000 ms — regra da interface. Em vez
  de contorná-la ou montar o sistema à mão só no teste, usamos o sistema real com a carga mais
  alta que ele aceita (32 e 40 sensores a 10 ms = ~3.200 e ~4.000 leituras/s).
- **Por que "faixas diferentes"?** Desde 15/11 cada sensor tem a própria faixa aceita. Um único
  `AnalisadorDeAnomalias` (imutável) é compartilhado pelos 4 consumidores e escolhe a faixa pelo id
  da leitura. Além das 5 checagens, o cenário confere, para **cada alerta de temperatura gravado**,
  que a regra violada cita o limite do próprio sensor e que o valor está fora da faixa dele — se
  um consumidor usasse a faixa de outro sensor, o teste falharia.
- **Por que "banco lento"?** Com o banco rápido os 4 consumidores dão conta e a fila não chega a
  encher. O atraso artificial de 30 ms por alerta (sobre o H2 real) simula um servidor de banco
  distante: os consumidores ficam para trás, a fila de 50 enche e os sensores **precisam
  esperar** — é o cenário que prova a contrapressão sem perda.

## Resultados (5 rodadas por cenário, todas aprovadas)

Rodado de novo em 15/11/2026, depois da mudança para **faixa por sensor** e configuração gravada
no banco (Prompts 1–5 do plano de configurações). Linux, JDK 21 compilando para Java 17.
20 de 20 rodadas aprovadas; nenhuma perdida, nenhuma duplicada, nenhum deadlock.

### 32 sensores

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 15.788 | 15.788 | 0 | 1.572 (1.572) | 0 | 3.157 |
| 2/5 | 15.744 | 15.744 | 0 | 1.588 (1.588) | 0 | 3.148 |
| 3/5 | 15.806 | 15.806 | 0 | 1.573 (1.573) | 0 | 3.161 |
| 4/5 | 15.778 | 15.778 | 0 | 1.716 (1.716) | 0 | 3.155 |
| 5/5 | 15.767 | 15.767 | 0 | 1.689 (1.689) | 0 | 3.153 |

### 40 sensores (carga máxima)

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 19.587 | 19.587 | 0 | 1.906 (1.906) | 0 | 3.917 |
| 2/5 | 19.678 | 19.678 | 0 | 1.838 (1.838) | 0 | 3.935 |
| 3/5 | 19.682 | 19.682 | 0 | 2.174 (2.174) | 0 | 3.936 |
| 4/5 | 19.712 | 19.712 | 0 | 1.915 (1.915) | 0 | 3.942 |
| 5/5 | 19.743 | 19.743 | 0 | 2.260 (2.260) | 0 | 3.948 |

### Banco lento — fila cheia, sensores esperando

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s |
|---|---|---|---|---|---|---|
| 1/5 | 6.028 | 6.028 | 0 | 655 (655) | 5.006 | 1.205 |
| 2/5 | 6.728 | 6.728 | 0 | 656 (656) | 5.460 | 1.345 |
| 3/5 | 7.297 | 7.297 | 0 | 655 (655) | 5.194 | 1.459 |
| 4/5 | 5.713 | 5.713 | 0 | 656 (656) | 4.793 | 1.142 |
| 5/5 | 7.040 | 7.040 | 0 | 654 (654) | 5.595 | 1.408 |

### Faixas diferentes no mesmo tipo (11 sensores)

| Rodada | Produzidas | Processadas | Perdidas | Alertas (no banco) | Esperas por fila cheia | Leituras/s | Alertas de temperatura conferidos |
|---|---|---|---|---|---|---|---|
| 1/5 | 5.420 | 5.420 | 0 | 629 (629) | 0 | 1.084 | 478 |
| 2/5 | 5.423 | 5.423 | 0 | 568 (568) | 0 | 1.084 | 395 |
| 3/5 | 5.423 | 5.423 | 0 | 601 (601) | 0 | 1.084 | 427 |
| 4/5 | 5.423 | 5.423 | 0 | 498 (498) | 0 | 1.084 | 366 |
| 5/5 | 5.423 | 5.423 | 0 | 605 (605) | 0 | 1.084 | 428 |

Todos os alertas de temperatura gravados usaram a faixa do próprio sensor (regra violada com o
limite certo e valor fora daquela faixa). A vazão menor é só porque são 11 sensores (~1.100
leituras/s), não 40.

"Esperas por fila cheia" = quantas vezes um sensor chegou com a fila cheia e ficou bloqueado no
`put()` até um consumidor abrir espaço. Mesmo com milhares de esperas, **nenhuma leitura foi
perdida**: a vazão cai (o sistema se adapta ao consumidor mais lento), mas nada é descartado.

**Por que o banco lento ficou mais lento que antes (~1.380 contra ~2.600 leituras/s)?** Não é
regressão: com 4 consumidores e 30 ms por alerta, o banco aguenta no máximo 4 / 0,030 ≈ 133
alertas/s. Com ~5 % de alertas (gerador antigo) isso limita a vazão a 133 / 0,05 ≈ 2.670
leituras/s; com ~10 % (gerador novo), a 133 / 0,10 ≈ 1.330 — o que foi medido (1.270 a 1.520).
Os cenários sem atraso (limitados pelos sensores, não pelo banco) não mudaram.

### Histórico — 10/11/2026 (eventos discretos, faixa única por tipo)

| Cenário | Leituras/s | Perdidas | Esperas por fila cheia |
|---|---|---|---|
| 32 sensores | 3.164 – 3.170 | 0 em todas | 0 |
| 40 sensores | 3.942 – 3.964 | 0 em todas | 0 |
| Banco lento | 1.272 – 1.518 | 0 em todas | 4.723 – 5.579 |

Os números de 15/11 ficaram no mesmo patamar: a faixa por sensor não custou vazão.

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
