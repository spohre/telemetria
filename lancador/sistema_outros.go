//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
)

func falhar(oQue, detalhe string) {
	fmt.Fprintln(os.Stderr, titulo+": "+oQue+"\n\n"+detalhe)
	os.Exit(1)
}

func esconderJanela(cmd *exec.Cmd) {}
