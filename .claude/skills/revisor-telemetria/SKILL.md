---
name: revisor-telemetria
description: Revisa o projeto Java "Sistema de Recepção e Análise de Telemetria" (POO, 2º bimestre, opção B) contra os requisitos e a rubrica de 5,0 pontos. Use quando pedirem para revisar, corrigir, avaliar, dar nota ou checar se o trabalho de telemetria atende à especificação.
---

# Revisor — Sistema de Telemetria (POO Java · 2º bimestre)

Você revisa o projeto como o professor corrigiria: verifica cada requisito obrigatório
**no código-fonte**, aponta evidências (arquivo:linha), estima a nota por critério e
diz exatamente o que corrigir. Seja rigoroso, mas justo: não invente problemas e não
dê pontos sem evidência.

## Passo 1 — Localizar o projeto
Ache a raiz (onde estão `src/` ou os pacotes `domain/`, `infra/`, `app/`). Se houver
vários projetos (ex.: pasta com entregas de vários alunos), revise um por vez.

## Passo 2 — Checagens automáticas
Rode o script desta skill (está na mesma pasta deste SKILL.md):

```bash
bash <pasta-desta-skill>/verificar.sh <raiz-do-projeto>
```

Ele ignora código em pastas `demo/` (simuladores não contam como requisito). Cobre estrutura de pacotes, classes obrigatórias, `Runnable`, fila thread-safe,
`PreparedStatement`/try-with-resources, SQL isolado, banco não-volátil, I/O de log,
pureza do `domain/`, contagem de testes e entregáveis. Trate `[FALHA]` como
problema confirmado só depois de olhar o trecho; `[ATENÇÃO]` exige leitura manual.
Se o script não estiver disponível, faça as mesmas verificações com Grep/Read.

## Passo 3 — Compilar e testar (se houver ambiente)
- Maven: `mvn -q test` · Gradle: `gradle test` · sem build: `javac` nos fontes.
- Registre quantos testes passaram/falharam. Se não for possível rodar (sem rede,
  sem driver), diga isso explicitamente — nunca afirme que "os testes passam" sem rodar.

## Passo 4 — Revisão manual (o que script nenhum pega)
Leia as classes centrais e responda cada pergunta com evidência.

**Sensores concorrentes — 1,2 pt**
- Há ≥ 3 sensores rodando ao mesmo tempo (threads distintas, não um loop sequencial)?
- O laço do sensor termina com interrupção (`while (!Thread.currentThread().isInterrupted())`)
  e o `catch (InterruptedException)` restaura o flag com `interrupt()`?
- Nenhuma leitura se perde: `put()` (bloqueia) em vez de `offer()` sem checar retorno.

**Fila sincronizada, sem race condition/deadlock — 1,3 pt**
- A fila é o único ponto de contato produtor ↔ consumidor?
- Sincronização manual: `wait()` dentro de `while`, `notifyAll()`, um único monitor,
  nada de I/O ou SQL **dentro** de bloco `synchronized` (segura lock por muito tempo).
- Mais de um lock? Verifique ordem de aquisição consistente (causa clássica de deadlock).
- Encerramento: o consumidor sai de forma limpa (poison pill ou interrupção) e drena a
  fila antes de terminar, sem perder as últimas leituras? `ExecutorService` é
  desligado com `shutdown()` + `awaitTermination()`?
- Teste de estresse (semana 5): existe evidência de que leituras produzidas = leituras
  consumidas (contador atômico, contagem no log)?

**Persistência JDBC — 1,0 pt**
- `Connection`, `PreparedStatement` e `ResultSet` sempre em try-with-resources.
- Nenhuma `Connection` compartilhada entre threads sem proteção.
- O banco é persistente (arquivo/servidor), não `:mem:` no código principal, e o script
  de criação não faz `DROP TABLE` a cada execução → alertas sobrevivem entre execuções.
- `SQLException` não é engolida com `catch` vazio / só `printStackTrace()` silencioso.

**Separação domínio × infraestrutura (DDD) — 0,5 pt**
- `domain/` não importa `java.sql`, `java.io.File*`, `java.nio.file` nem `infra`.
- `Sensor` só publica leituras; não conhece banco nem log.
- `AnalisadorDeAnomalias` é regra pura: recebe leitura, devolve alerta (ou Optional/null),
  sem efeitos colaterais.
- `LeituraTelemetria`/`Alerta` imutáveis (record ou campos `final`).

**Testes unitários — 0,5 pt**
- ≥ 5 testes das regras de anomalia, em JUnit, sem threads nem banco.
- Cobrem fronteiras (exatamente no limite, logo acima/abaixo) e cada tipo de sensor,
  inclusive faixa mínima e máxima (ex.: velocidade fora da faixa).
- Asserções significativas (não só `assertNotNull`).

**Organização e relatório — 0,5 pt**
- Pacotes coerentes, nomes claros, sem código morto/comentado em massa.
- Relatório explica as regras de anomalia (limites por tipo) e como a concorrência foi tratada.

## Passo 5 — Entregáveis
Confirme: código em `domain/infra/app` · script SQL de criação da tabela de alertas ·
arquivo de log de uma execução de exemplo · relatório curto. Falta de entregável é
apontada mesmo que o código esteja perfeito.

## Passo 6 — Relatório de revisão
Entregue neste formato (em português):

```
## Revisão — Sistema de Telemetria
Veredito: ATENDE / ATENDE PARCIALMENTE / NÃO ATENDE   ·   Nota estimada: X,X / 5,0

| Critério                                   | Peso | Estimado | Evidência / problema principal |
|--------------------------------------------|------|----------|--------------------------------|
| Sensores concorrentes sem perda            | 1,2  |          |                                |
| Fila sincronizada (sem race/deadlock)      | 1,3  |          |                                |
| Persistência JDBC                          | 1,0  |          |                                |
| Separação domínio × infra (DDD)            | 0,5  |          |                                |
| Testes unitários (≥5, regras de anomalia)  | 0,5  |          |                                |
| Organização + relatório                    | 0,5  |          |                                |

### Requisitos técnicos obrigatórios
✅/❌ Sensor implements Runnable · ✅/❌ Fila thread-safe · ✅/❌ AnalisadorDeAnomalias isolado
✅/❌ Alerta + Repository/DAO · ✅/❌ I/O de log · ✅/❌ ≥5 testes JUnit

### Problemas (ordem de impacto na nota)
1. [CRÍTICO|IMPORTANTE|MENOR] arquivo:linha — o que está errado → como corrigir (trecho de código se curto)

### Entregáveis
✅/❌ código em pacotes · ✅/❌ script SQL · ✅/❌ log de exemplo · ✅/❌ relatório

### O que rodou
Script de checagem: N OK · N FALHA · N ATENÇÃO. Testes: N passaram / N falharam (ou: não executados — motivo).
```

Desconte proporcionalmente: requisito ausente zera o critério; implementado com falha
grave de concorrência (race condition real, leitura perdida, deadlock possível) perde
pelo menos metade; problemas menores descontam 0,1–0,2. Explique cada desconto.
