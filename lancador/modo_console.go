//go:build !janela

package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
)

// Telemetria-Console.exe: roda sem janela e mostra as mensagens no console.
// Sem argumentos (ex.: duplo clique) usa "--console 10" e espera Enter no fim,
// para o console não fechar antes de ler o extrato.
var semArgumentos = len(os.Args) == 1

func argumentosDoPrograma() []string {
	if semArgumentos {
		return []string{"--console", "10"}
	}
	return os.Args[1:]
}

func executar(j *java, pasta string, argumentos []string) int {
	cmd := exec.Command(j.console, argumentos...)
	cmd.Dir = pasta
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	codigo := 0
	if err := cmd.Run(); err != nil {
		if saida, ok := err.(*exec.ExitError); ok {
			codigo = saida.ExitCode()
		} else {
			fmt.Fprintln(os.Stderr, "Não foi possível executar o Java:", err)
			codigo = 1
		}
	}
	if semArgumentos {
		fmt.Print("\nPressione Enter para fechar...")
		bufio.NewReader(os.Stdin).ReadString('\n')
	}
	return codigo
}
