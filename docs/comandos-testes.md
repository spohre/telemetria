# Comandos para rodar cada teste individualmente

Rode no PowerShell, a partir de `C:\Users\andre\GitHub\telemetria-main`.
Cada linha executa **um único** método de teste (`-Dtest=Classe#metodo`).

- As aspas simples são necessárias no PowerShell, que não deve interpretar `#`, `$` ou `.` dentro do argumento.
- `AnalisadorDeAnomaliasTest*` tem o `*` porque os testes ficam em classes internas (`@Nested`). O `*` encontra o método em qualquer uma delas.
- `fronteirasNaTemperatura` (8 casos) e `fronteirasAbaixoDaFaixaNaVelocidade` (6 casos) são parametrizados. Um comando roda todos os casos daquele método.
- `TesteDeEstresse` precisa de `'-Dgroups=estresse'`, porque o `pom.xml` exclui essa tag por padrão. Cada um repete 5 vezes e leva cerca de 25 s.
- Todos os testes de uma vez: `mvn test` (111 testes). Só os de estresse: `mvn test '-Dgroups=estresse'`.

## Regras de anomalia: os testes unitários exigidos pela especificação (≥ 5, sem threads nem banco)

### AnalisadorDeAnomaliasTest
```powershell
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#temperaturaAcimaDoLimiteGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#temperaturaExatamenteNoLimiteNaoGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#velocidadeAbaixoDaFaixaGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#velocidadeAcimaDaFaixaGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#velocidadeNosLimitesDaFaixaNaoGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#pressaoAbaixoDaFaixaGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#pressaoAcimaDaFaixaGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#pressaoNosLimitesDaFaixaNaoGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#cadaTipoUsaSuaPropriaRegra'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#alertaCarregaSensorValorMomentoERegraCorretos'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#umidadeAbaixoDaFaixaGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#umidadeAcimaDaFaixaGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#umidadeNosLimitesDaFaixaNaoGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#temperaturaAbaixoDeZeroGeraAlerta'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#fronteirasNaTemperatura'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#fronteirasAbaixoDaFaixaNaVelocidade'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#severidadeUsaALarguraDaFaixaDoProprioTipo'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#pressaoComDesvioGrandeECritica'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#umidadeAbaixoComSeveridadeMedia'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#regraVioladaAcimaDoLimiteIndicaLimiteEUnidade'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#regraVioladaAbaixoDoLimiteIndicaLimiteEUnidade'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#regrasListaUmaFaixaPorTipoNaOrdemDoEnum'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#escalaDeSeveridadeEmUnidadesDoSensor'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#escalaBateComASeveridadeCalculadaNasFronteiras'
mvn test '-Dtest=AnalisadorDeAnomaliasTest*#regrasNaoPodemSerAlteradasPorQuemConsulta'
```

## Demais testes

### LeituraTelemetriaTest
```powershell
mvn test '-Dtest=LeituraTelemetriaTest#rejeitaSensorIdVazio'
mvn test '-Dtest=LeituraTelemetriaTest#rejeitaValorNaNOuInfinito'
mvn test '-Dtest=LeituraTelemetriaTest#rejeitaSequenciaNegativa'
mvn test '-Dtest=LeituraTelemetriaTest#construtorSemSequenciaUsaZero'
```

### SensorTest
```powershell
mvn test '-Dtest=SensorTest#sequenciasSaemUmDoisTresSemBuracos'
mvn test '-Dtest=SensorTest#leituraCarregaIdTipoValorEMomentoDoRelogio'
mvn test '-Dtest=SensorTest#interrupcaoEncerraAThreadEmMenosDeUmSegundo'
mvn test '-Dtest=SensorTest#interrupcaoEncerraMesmoComPublicarBloqueado'
mvn test '-Dtest=SensorTest#pausadoDesdeOInicioNaoPublica'
mvn test '-Dtest=SensorTest#pausarNoMeioParaDePublicarERetomarVolta'
mvn test '-Dtest=SensorTest#interrupcaoDuranteAPausaEncerraAThread'
mvn test '-Dtest=SensorTest#variosSensoresEmThreadsSeparadasTemSequenciasIndependentes'
mvn test '-Dtest=SensorTest#rejeitaParametrosInvalidos'
```

### GeradorPasseioAleatorioTest
```powershell
mvn test '-Dtest=GeradorPasseioAleatorioTest#mesmaSementeGeraMesmaSequencia'
mvn test '-Dtest=GeradorPasseioAleatorioTest#semPicosNuncaSaiDaFaixa'
mvn test '-Dtest=GeradorPasseioAleatorioTest#cercaDeCincoPorCentoDasLeiturasSaoPicosForaDaFaixa'
mvn test '-Dtest=GeradorPasseioAleatorioTest#sempreComPicosTodosOsValoresSaemDaFaixaEmAmbosOsLados'
mvn test '-Dtest=GeradorPasseioAleatorioTest#valoresTemNoMaximoUmaCasaDecimal'
mvn test '-Dtest=GeradorPasseioAleatorioTest#rejeitaProbabilidadeInvalida'
```

### FilaDeLeiturasTest
```powershell
mvn test '-Dtest=FilaDeLeiturasTest#capacidadePadraoEConfiguravel'
mvn test '-Dtest=FilaDeLeiturasTest#retiraNaOrdemEmQueFoiPublicado'
mvn test '-Dtest=FilaDeLeiturasTest#variosProdutoresEUmConsumidorSemPerdaNemDuplicacao'
mvn test '-Dtest=FilaDeLeiturasTest#filaCheiaBloqueiaOProdutorEmVezDeDescartar'
mvn test '-Dtest=FilaDeLeiturasTest#produtorBloqueadoPodeSerInterrompido'
mvn test '-Dtest=FilaDeLeiturasTest#pilulasSaemDepoisDasLeiturasENaoContamComoRetiradas'
mvn test '-Dtest=FilaDeLeiturasTest#rejeitaLeituraNula'
```

### LogArquivoTest
```powershell
mvn test '-Dtest=LogArquivoTest#criaPastaEArquivoComCabecalho'
mvn test '-Dtest=LogArquivoTest#cabecalhoSoUmaVezMesmoReabrindoOArquivo'
mvn test '-Dtest=LogArquivoTest#arquivoExistenteVazioRecebeCabecalho'
mvn test '-Dtest=LogArquivoTest#variasThreadsRegistrandoAoMesmoTempoNenhumaLinhaPerdidaOuMisturada'
mvn test '-Dtest=LogArquivoTest#acentosEGrauCelsiusPreservadosEmUtf8'
mvn test '-Dtest=LogArquivoTest#fecharEscreveTudoQueAindaEstavaPendente'
mvn test '-Dtest=LogArquivoTest#registrarDepoisDeFecharEhErroEFecharDuasVezesNaoEh'
mvn test '-Dtest=LogArquivoTest#caminhoInvalidoFalhaNaHora'
```

### AlertaRepositoryIntegracaoTest
```powershell
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#salvarERecuperar'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#rejeitaAlertaJaPersistido'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#buscarPorSensor'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#sqlInjectionNaoAfetaBanco'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#salvarEmLote'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#gravacaoConcorrenteNaoPerdeAlertas'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#buscarSemFiltros'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#buscarComFiltrosCombinados'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#buscarDesde'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#buscarSensorEmBrancoELimite'
mvn test '-Dtest=AlertaRepositoryIntegracaoTest#buscarComTextoMalicioso'
```

### FormatoMensagensTest
```powershell
mvn test '-Dtest=FormatoMensagensTest#linhaLogAnomala'
mvn test '-Dtest=FormatoMensagensTest#linhaLogNormal'
mvn test '-Dtest=FormatoMensagensTest#consoleAlerta'
mvn test '-Dtest=FormatoMensagensTest#extratoDestacaProblemas'
mvn test '-Dtest=FormatoMensagensTest#extratoLimpo'
mvn test '-Dtest=FormatoMensagensTest#duracao'
mvn test '-Dtest=FormatoMensagensTest#decimalCompacto'
```

### ProcessadorDeLeiturasTest
```powershell
mvn test '-Dtest=ProcessadorDeLeiturasTest#leituraNormalNaoGeraAlertaMasVaiParaOLog'
mvn test '-Dtest=ProcessadorDeLeiturasTest#leituraAnomalaGeraAlertaSalvaRegistraENotifica'
mvn test '-Dtest=ProcessadorDeLeiturasTest#erroDeBancoNaoParaOConsumidorEAvisaUmaVez'
mvn test '-Dtest=ProcessadorDeLeiturasTest#bancoVoltaEOsAlertasSeguintesSaoSalvos'
mvn test '-Dtest=ProcessadorDeLeiturasTest#erroInesperadoNoOuvinteNaoPerdeALeitura'
mvn test '-Dtest=ProcessadorDeLeiturasTest#logQueParouDeGravarAvisaUmaVezENaoPerdeLeituras'
```

### ProcessadorDeLeiturasConcorrenciaTest
```powershell
mvn test '-Dtest=ProcessadorDeLeiturasConcorrenciaTest#consumidorDrenaAFilaETerminaNaPilula'
mvn test '-Dtest=ProcessadorDeLeiturasConcorrenciaTest#doisConsumidoresComDuasPilulasProcessamTudoExatamenteUmaVez'
mvn test '-Dtest=ProcessadorDeLeiturasConcorrenciaTest#interrupcaoAbortaOConsumidorParadoNaFilaVazia'
```

### ProcessadorComLogArquivoTest
```powershell
mvn test '-Dtest=ProcessadorComLogArquivoTest#todasAsLeiturasVaoParaOArquivoNoFormatoDoLog'
```

### ControladorTelemetriaTest
```powershell
mvn test '-Dtest=ControladorTelemetriaTest#configuracaoPadraoTemPeloMenosTresSensores'
mvn test '-Dtest=ControladorTelemetriaTest#cicloCompletoSemPerdaComLogEExtrato'
mvn test '-Dtest=ControladorTelemetriaTest#cadaSensorEConsumidorRodaNaSuaThreadEElasTerminamAoParar'
mvn test '-Dtest=ControladorTelemetriaTest#pausarCongelaAProducaoERetomarContinua'
mvn test '-Dtest=ControladorTelemetriaTest#pararDuranteAPausaTambemDrenaTudo'
mvn test '-Dtest=ControladorTelemetriaTest#estatisticasChegamAoOuvinteACadaSegundo'
mvn test '-Dtest=ControladorTelemetriaTest#naoIniciaDuasVezesENaoMudaConfiguracaoEmExecucao'
mvn test '-Dtest=ControladorTelemetriaTest#regrasExibidasTrazemFaixaEEscalaDeSeveridade'
mvn test '-Dtest=ControladorTelemetriaTest#buscarAlertasConverteOPeriodoEmInstanteEFiltra'
mvn test '-Dtest=ControladorTelemetriaTest#bancoForaDoArNaoPerdeLeiturasEExtratoMostraAlertasNaoGravados'
mvn test '-Dtest=ControladorTelemetriaTest#consoleMostraInicioAlertasEEncerramentoMasNaoLeiturasNormais'
mvn test '-Dtest=ControladorTelemetriaTest#exportarExtratoGravaArquivoUtf8'
```

### TesteDeEstresse
```powershell
mvn test '-Dtest=TesteDeEstresse#trintaEDoisSensores' '-Dgroups=estresse'
mvn test '-Dtest=TesteDeEstresse#cargaMaxima' '-Dgroups=estresse'
mvn test '-Dtest=TesteDeEstresse#bancoLentoForcaFilaCheia' '-Dgroups=estresse'
```

