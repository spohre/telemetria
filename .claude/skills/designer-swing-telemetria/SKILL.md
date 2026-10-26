---
name: designer-swing-telemetria
description: Use ao criar ou alterar telas Swing do Sistema de Telemetria (POO Java). Padroniza tema, cores, botões, ícones, telas, comportamento com threads e as mensagens de console, log e extrato.
---

# Designer Swing — Central de Telemetria

Visual de **sala de controle**: fundo grafite escuro, dados em fonte monoespaçada,
cor usada só para comunicar estado (normal × severidade do alerta). Nada decorativo.

## Regras (sempre)

1. **Antes do código, descreva a tela em texto**: áreas, campos, botões, de onde vêm os
   dados e qual thread os entrega. Use o formato da seção "Telas".
2. **Visual único**: cores, fontes, espaçamentos e raios vêm SÓ de `app.ui.tema.Tema`.
   Proibido `new Color(...)`, `new Font(...)` ou número mágico de pixel fora do Tema.
3. **Ícones** só de `app.ui.tema.Icones` (vetoriais Java2D, sem PNG externo).
   **Botões, combos, spinners, tabelas, rolagens, cartões e badges** só de `Componentes`
   (`botao`, `combo`, `spinner`, `tabela`, `rolagem`, `secao`, `cartao`, `badge`, `indicador`).
4. **Layout com gerenciadores** (`BorderLayout`, `GridBagLayout`, `BoxLayout`,
   `GridLayout`, `CardLayout`). Nunca `setLayout(null)` / `setBounds` / x,y fixo.
5. **A tela chama o controller** (`ControladorMonitoramento`). Regra de negócio
   (limites, anomalia, SQL, arquivo) nunca fica na View. A View não importa
   `java.sql`, `infra.*` nem `AnalisadorDeAnomalias`.
6. **Atualizações vindas de outras threads** (sensores, consumidor da fila) chegam
   pelo `OuvinteTelemetria` e passam por `SwingUtilities.invokeLater`. Nenhum
   componente Swing é tocado fora da EDT.
7. **Tarefas demoradas** (consulta ao banco, exportar extrato, encerrar threads):
   `SwingWorker`, com indicador "carregando…" e botão desabilitado enquanto roda.
   Nunca bloquear a EDT (`Thread.sleep`, `join`, `queue.take` na EDT = proibido).
8. **Toda mensagem de erro diz o que aconteceu e o que fazer** (`MensagemErro`):
   título curto, causa em linguagem simples, ação concreta; detalhe técnico recolhido.
9. **Regras continuam cobertas pelo JUnit, fora da View.** Teste de tela não substitui
   teste do `AnalisadorDeAnomalias`.
10. Fluxo intenso (centenas de leituras/s): **um `invokeLater` agrupado** — a thread
    põe a leitura numa `ConcurrentLinkedQueue` e só agenda `invokeLater` se nenhum estiver
    pendente (`AtomicBoolean.compareAndSet`); na EDT, drena tudo de uma vez. A tabela ao vivo
    mantém só as últimas 200 linhas.

## Tema

### Cores (`Tema.Cor`)
| Token | Hex | Uso |
|---|---|---|
| `FUNDO` | `#0E141B` | fundo da janela |
| `SUPERFICIE` | `#151E28` | painéis, cartões, tabelas |
| `SUPERFICIE_ELEVADA` | `#1C2733` | cabeçalhos, hover, campos |
| `BORDA` | `#2A3846` | divisórias e contornos |
| `TEXTO` | `#E6EDF3` | texto principal |
| `TEXTO_SECUNDARIO` | `#8B9AAB` | rótulos, unidades, metadados |
| `TEXTO_DESATIVADO` | `#56636F` | controles desabilitados |
| `PRIMARIA` | `#2BC4D8` | ação principal, foco, seleção (ciano "sinal") |
| `PRIMARIA_HOVER` | `#5AD5E5` | hover da ação principal |
| `SOBRE_PRIMARIA` | `#06232A` | texto em cima da primária |
| `NORMAL` | `#3FB950` | leitura dentro da faixa, sistema ok |
| `SEV_BAIXA` | `#58A6FF` | alerta BAIXA |
| `SEV_MEDIA` | `#E3B341` | alerta MEDIA |
| `SEV_ALTA` | `#F0883E` | alerta ALTA |
| `SEV_CRITICA` / `PERIGO` | `#F85149` | alerta CRITICA, botão Parar, erros |

Cor de severidade **sempre** acompanhada de texto ou ícone (daltonismo).
`Tema.corDa(Severidade)` é o único mapeamento severidade → cor.

### Tipografia (`Tema.Fonte`)
Família de interface: Inter → Segoe UI → SF Pro Text → SansSerif (primeira disponível).
Família de dados: JetBrains Mono → Cascadia Mono → Consolas → DejaVu Sans Mono → Monospaced.

| Token | Fonte | Uso |
|---|---|---|
| `TITULO` | UI bold 18 | nome da janela |
| `SECAO` | UI bold 13 | título de painel |
| `CORPO` | UI 13 | texto geral, botões |
| `ROTULO` | UI 11 | rótulos e metadados (TEXTO_SECUNDARIO) |
| `VALOR` | Mono bold 26 | valor atual no cartão do sensor |
| `DADO` | Mono 12 | tabelas, horários, números |

### Medidas (`Tema.Medida`)
Grade de 4 px: `ESP_1=4, ESP_2=8, ESP_3=12, ESP_4=16, ESP_6=24`. `RAIO=8`,
`ALTURA_BOTAO=34`, `ALTURA_LINHA_TABELA=28`, `ICONE_PEQ=16`, `ICONE=20`, `ICONE_GRANDE=28`.

## Botões (`Componentes.botao(texto, ícone, Estilo)`)
| Estilo | Visual | Onde |
|---|---|---|
| `PRIMARIO` | fundo PRIMARIA, texto SOBRE_PRIMARIA | Iniciar, Filtrar, Aplicar — **um por área** |
| `SECUNDARIO` | contorno BORDA, texto TEXTO, hover SUPERFICIE_ELEVADA | Pausar, Exportar extrato, Cancelar |
| `PERIGO` | contorno + texto PERIGO; hover preenchido | Parar sistema |
| `FANTASMA` | sem fundo, só ícone/texto TEXTO_SECUNDARIO | ações de barra (configurar, limpar) |

Todos: altura 34, raio 8, ícone 16 à esquerda, cursor de mão, anel de foco PRIMARIA,
desabilitado = TEXTO_DESATIVADO sem preenchimento. Botões perigosos pedem confirmação.
Estados por fase do sistema: **PARADO** → só Iniciar habilitado; **EXECUTANDO** →
Pausar e Parar; **PAUSADO** → Retomar (primário) e Parar.

## Ícones (`Icones.de(Icone, tamanho, cor)`)
Traço 1,75 px em grade 24, pontas arredondadas, monocromáticos (cor pelo contexto).

| Ícone | Desenho | Uso |
|---|---|---|
| `INICIAR` / `PAUSAR` / `PARAR` | triângulo / duas barras / quadrado | controle do sistema |
| `TEMPERATURA` | termômetro | sensor de temperatura |
| `VELOCIDADE` | velocímetro (arco + ponteiro) | sensor de velocidade |
| `PRESSAO` | manômetro (círculo + ponteiro) | sensor de pressão |
| `UMIDADE` | gota | sensor de umidade |
| `SENSOR` | ondas de sinal | marca do sistema, sensores |
| `ALERTA` | triângulo com ! | alertas |
| `OK` | círculo com check | estado normal |
| `ERRO` | círculo com × | diálogos de erro |
| `BANCO` | cilindro | conexão JDBC, histórico |
| `HISTORICO` | relógio | aba Histórico |
| `REGRAS` | controles deslizantes | aba Regras e sensores |
| `FILTRO` | funil | filtrar |
| `EXPORTAR` | seta saindo da caixa | exportar extrato |
| `LOG` | documento com linhas | arquivo de log |
| `FILA` | pilha de barras | tamanho da fila |

`Icones.doTipo(TipoSensor)` mapeia tipo → ícone.

## Telas

Formato obrigatório da descrição (copie e preencha antes de codar):
`Propósito · Áreas (de cima para baixo) · Componentes por área · Dados: origem e thread · Estados · Erros`.

### Janela principal (`JanelaPrincipal`, 1280×800, mín. 1024×680)
- **Barra superior**: ícone SENSOR + "Central de Telemetria" (TITULO); indicador de fase
  (●NORMAL "Em execução" / ●SEV_MEDIA "Pausado" / ●TEXTO_SECUNDARIO "Parado") + tempo
  decorrido; à direita Iniciar/Pausar/Parar.
- **Navegação**: abas segmentadas (botões + `CardLayout`): Monitoramento · Histórico de
  alertas · Regras e sensores.
- **Barra de status** (rodapé, DADO): `Leituras 12.430 · Fila 3 · Alertas 87 ·
  Threads 6 · Perdidas 0 · Banco ● conectado · Log data/leituras.log`.
  "Perdidas" > 0 fica em PERIGO.

### Aba Monitoramento (`PainelMonitoramento`)
```
┌ Sensores ──────────────────────────────────────────────────────────────────┐
│ [PRES-01] [PRES-02] [TEMP-01] [TEMP-02] [UMID-01]   ← colunas pela largura  │
│ [UMID-02] [VEL-01]  [VEL-02]                          (mín. 230 px), ordem   │
│                                                       por id, 2 linhas e rola│
├ Leituras em tempo real ──── 62% ─────┬ Alertas recentes ──── 38% ──────────┤
│ Hora | Sensor | Tipo | Valor | Estado │ [ALTA] TEMP-01 92,5 °C     19:46:02 │
│ últimas 200, mais recente no topo     │ acima do limite 80,0 °C · salvo #128│
└───────────────────────────────────────┴─────────────────────────────────────┘
```
- **Cartão de sensor**: faixa lateral na cor do estado, ícone do tipo, id, badge
  (NORMAL/severidade), valor grande + unidade, barra valor × faixa aceita, "faixa 0,0 – 80,0 °C",
  "Tipo · última HH:mm:ss".
- **Alertas recentes**: máx. 50; contador e botão Limpar (limpa só a lista, não o banco).
- Dados: leituras e alertas chegam da thread consumidora via `OuvinteTelemetria` →
  `invokeLater` agrupado. Estatísticas a cada 1 s.
- Vazio: "Nenhuma leitura ainda — clique em Iniciar para ligar os sensores."

### Aba Histórico de alertas (`PainelHistorico`)
- **Título** da seção e, numa **linha própria** abaixo, os filtros: Sensor (combo editável,
  "Todos"), Tipo, Severidade, Período (tudo / última hora / 24 h), [Filtrar] primário,
  [Exportar extrato] secundário.
- **Tabela**: Nº · Data/hora · Sensor · Tipo · Valor · Regra violada · Severidade.
- **Rodapé**: "128 alertas encontrados no banco" ou "Consultando o banco…".
- Dados: `controlador.buscarAlertas(filtro)` dentro de `SwingWorker`; botões desabilitados
  durante a consulta. Mostra alertas de execuções anteriores (prova de persistência).
- Erro de banco → `MensagemErro` (ver abaixo); a tabela anterior permanece.

### Aba Regras e sensores (`PainelRegras`)
- **Regras de anomalia** (somente leitura, vindas do controller): Tipo · Faixa aceita ·
  Severidade se violar. Texto: "Regras definidas no AnalisadorDeAnomalias (cobertas por JUnit)".
- **Simulação**: sensores por tipo (spinner 1–10), intervalo entre leituras (ms, 10–5000),
  consumidores (1–4). Editável só com o sistema PARADO; [Aplicar] primário.

### Diálogo Extrato da execução (`DialogoExtrato`, ao Parar)
Resumo do mesmo conteúdo do arquivo de extrato (ver abaixo), com [Exportar] e [Fechar].

### Diálogo de erro (`Componentes.mostrarErro`)
Ícone ERRO, título, "O que aconteceu", "O que fazer", detalhe técnico recolhido.

## Mensagens (console, log e extrato)

### Console — para humanos acompanharem
`HH:mm:ss.SSS  NÍVEL   [origem]      mensagem` — colunas alinhadas, sem stack trace
(stack trace vai para o log). Níveis: `INFO`, `ALERTA`, `AVISO`, `ERRO`.
Separadores **ASCII** (`:` e `|`): `·` e `—` viram `?` no cmd do Windows e em terminais sem
UTF-8. Gere as linhas com `FormatoMensagens.console(...)` / `consoleAlerta(...)`.
```
19:46:02.118  INFO    [sistema]     3 sensores e 1 consumidor iniciados (intervalo 200 ms)
19:46:02.431  ALERTA  [TEMP-01]     92,5 °C: acima do limite 80,0 °C | ALTA | salvo #128
19:46:09.004  AVISO   [fila]        Fila com 950/1000 leituras: consumidor não acompanha
19:46:12.770  ERRO    [banco]       Alerta não salvo: banco indisponível. Verifique db.url em db.properties
19:46:30.002  INFO    [sistema]     Encerrado: 1.204 leituras, 0 perdidas, 37 alertas. Extrato: data/extrato-20261015-194630.txt
```
Não imprimir cada leitura normal no console (polui); essas vão só para o log.

### Log de leituras (arquivo) — para máquinas e auditoria
Arquivo `data/leituras.log`, UTF-8, uma linha por leitura (todas, não só anômalas),
separador `;`, cabeçalho na 1ª linha, horário ISO-8601, ponto decimal:
```
momento;sensor;tipo;valor;unidade;resultado;severidade;thread
2026-10-15T19:46:02.431-03:00;TEMP-01;TEMPERATURA;92.5;°C;ANOMALA;ALTA;sensor-TEMP-01
2026-10-15T19:46:02.433-03:00;VEL-01;VELOCIDADE;64.0;km/h;NORMAL;;sensor-VEL-01
```
Escrita por uma única thread (o consumidor) com `BufferedWriter` em try-with-resources.

### Extrato da execução (arquivo + diálogo) — resumo final
`data/extrato-AAAAMMDD-HHmmss.txt`:
```
EXTRATO DA EXECUÇÃO — Central de Telemetria
Período ........ 15/10/2026 19:46:02 → 19:46:30 (28 s)
Configuração ... 3 sensores · 1 consumidor · intervalo 200 ms

LEITURAS
  Produzidas ......... 1.204
  Processadas ........ 1.204
  Perdidas ........... 0          (produzidas − processadas)

ALERTAS POR TIPO / SEVERIDADE
  TEMPERATURA   BAIXA 0  MEDIA 4  ALTA 9  CRITICA 1   total 14
  VELOCIDADE    BAIXA 2  MEDIA 6  ALTA 3  CRITICA 0   total 11
  ...
  TOTAL ......... 37 alertas  (gravados no banco: 37)

ARQUIVOS
  Log de leituras  data/leituras.log
  Banco            jdbc:h2:./data/telemetria
```
Números com separador de milhar pt-BR; "Perdidas" diferente de 0 é destacada com `<<< VERIFICAR`.

## Estrutura de pacotes
```
app/ControladorMonitoramento   (interface que a View chama)
app/OuvinteTelemetria          (callbacks das threads → View, fora da EDT)
app/FormatoMensagens           (console, log e extrato — funções puras, testadas com JUnit)
app/EstatisticasExecucao, ExtratoExecucao, FaseSistema, FiltroAlertas,
    RegraExibida, ConfiguracaoSimulacao
app/ui/tema/Tema, Icones, Componentes, MensagemErro
app/ui/telas/JanelaPrincipal, PainelMonitoramento, CartaoSensor,
             PainelHistorico, PainelRegras, DialogoExtrato
app/ui/demo/ControladorSimulado, DemoInterface   (interface rodando sem o backend real)
```

## Armadilhas já encontradas (não repetir)
- **Conteúdo de rolagem cortado à direita**: o que vai dentro de `Componentes.rolagem(...)`
  precisa acompanhar a largura — use `Componentes.PainelRolavel` (implementa `Scrollable`).
- **Texto de botão truncado** ("Ret…"): nunca `setPreferredSize` com largura fixa em botão
  cujo texto muda; `Componentes.botao` já fixa só a altura.
- **Parágrafo cortado no `pack()`**: `JTextArea` com quebra informa 1 linha antes de ter
  largura; use `Componentes.paragrafo(texto)` (rótulo HTML com largura definida).
- **Componente achatado em `BoxLayout`**: ele respeita o tamanho MÁXIMO; defina-o.
- **Mudar ícone/cor dentro de `paint()`** dispara repaint em loop: mude por listener de estado.
- **Acentos quebrados** (`Press?o`): compile com `-encoding UTF-8` (o `pom.xml` já define).
- **Filtros invadindo o título da seção**: muitos controles vão numa linha própria no
  corpo da seção, não como "ações" do cabeçalho.

## Checklist antes de entregar uma tela
- [ ] Descrição em texto escrita antes do código
- [ ] Zero `new Color`/`new Font`/`setBounds` fora do Tema
- [ ] Botões, combos, spinners, tabelas e rolagens criados por `Componentes`
- [ ] Nenhum import de `java.sql`, `infra.*` ou do analisador na View
- [ ] Toda chamada do `OuvinteTelemetria` embrulhada em `invokeLater`
- [ ] Banco/arquivo/encerramento em `SwingWorker`, com estado de carregamento
- [ ] Erros via `MensagemErro` (o que aconteceu + o que fazer)
- [ ] Testes JUnit das regras continuam passando
