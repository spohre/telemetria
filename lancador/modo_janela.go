//go:build janela

package main

import (
	"os"
	"os/exec"
)

// Telemetria.exe: abre a interface com javaw (sem console). Espera a janela fechar para,
// se o Java terminar com erro, mostrar o motivo numa caixa de mensagem.
func argumentosDoPrograma() []string { return os.Args[1:] }

func executar(j *java, pasta string, argumentos []string) int {
	cmd := exec.Command(j.janela, argumentos...)
	cmd.Dir = pasta
	erros := &guardarFinal{}
	cmd.Stderr = erros
	if err := cmd.Run(); err != nil {
		falhar("A Central de Telemetria fechou com erro.", detalheDoErro(err, erros))
	}
	return 0
}
