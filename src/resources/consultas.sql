-- =====================================================================
-- Consultas de verificação — rodar APÓS encerrar o programa
-- (prova que os alertas ficaram persistidos entre execuções).
-- No H2: java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar \
--        org.h2.tools.Shell -url jdbc:h2:./data/telemetria -user sa
-- =====================================================================

-- 1. Total de alertas gravados
SELECT COUNT(*) AS total_alertas FROM alerta;

-- 2. Alertas por tipo de sensor e severidade
SELECT tipo_sensor, severidade, COUNT(*) AS quantidade
  FROM alerta
 GROUP BY tipo_sensor, severidade
 ORDER BY tipo_sensor, severidade;

-- 3. Últimos 10 alertas
SELECT id, sensor_id, tipo_sensor, valor_lido, regra_violada, severidade, momento_leitura
  FROM alerta
 ORDER BY momento_leitura DESC
 FETCH FIRST 10 ROWS ONLY;

-- 4. Sensor com mais alertas
SELECT sensor_id, COUNT(*) AS quantidade
  FROM alerta
 GROUP BY sensor_id
 ORDER BY quantidade DESC
 FETCH FIRST 1 ROWS ONLY;

-- 5. Maior valor registrado em alerta por tipo
SELECT tipo_sensor, MAX(valor_lido) AS valor_maximo
  FROM alerta
 GROUP BY tipo_sensor;

-- Limpar a tabela (somente em ambiente de teste!)
-- DELETE FROM alerta;
