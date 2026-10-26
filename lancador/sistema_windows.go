//go:build windows

package main

import (
	"os"
	"os/exec"
	"syscall"
	"unsafe"
)

var (
	user32         = syscall.NewLazyDLL("user32.dll")
	procMessageBox = user32.NewProc("MessageBoxW")
)

const (
	mbOk           = 0x00000000
	mbIconErro     = 0x00000010
	criarSemJanela = 0x08000000 // CREATE_NO_WINDOW
)

// falhar mostra uma caixa de mensagem do Windows (o Telemetria.exe não tem console) e sai.
func falhar(oQue, detalhe string) {
	texto, _ := syscall.UTF16PtrFromString(oQue + "\n\n" + detalhe)
	tit, _ := syscall.UTF16PtrFromString(titulo)
	procMessageBox.Call(0, uintptr(unsafe.Pointer(texto)), uintptr(unsafe.Pointer(tit)), mbOk|mbIconErro)
	os.Exit(1)
}

// esconderJanela evita que um console pisque ao rodar "java -version".
func esconderJanela(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: criarSemJanela}
}
