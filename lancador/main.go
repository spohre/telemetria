// Lançador da Central de Telemetria.
//
// Encontra um Java 17+ instalado na máquina e executa o telemetria.jar que está na mesma
// pasta do .exe, usando essa pasta como diretório de trabalho (é onde ficam data/, o banco
// e o log). Não depende de nada além do próprio Windows e do Java já instalado.
//
// Dois executáveis saem deste mesmo código (Go 1.21+, compila a partir de qualquer sistema):
//
//	set GOOS=windows& set GOARCH=amd64
//	go build -trimpath -ldflags "-s -w -H=windowsgui" -tags janela -o Telemetria.exe .
//	go build -trimpath -ldflags "-s -w" -o Telemetria-Console.exe .
//
// O ícone vem de rsrc_windows_amd64.syso, gerado por gerar_syso.py a partir de
// telemetria.ico (o Go embute automaticamente no .exe os arquivos .syso da pasta).
package main

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
)

const (
	javaMinimo = 17
	nomeJar    = "telemetria.jar"
	titulo     = "Central de Telemetria"
)

// java encontrado: executável de console (java) e de janela (javaw), versão principal.
type java struct {
	console, janela string
	versao          int
}

func main() {
	pasta, err := pastaDoExecutavel()
	if err != nil {
		falhar("Não foi possível descobrir a pasta do programa.", err.Error())
	}
	jar := filepath.Join(pasta, nomeJar)
	if _, err := os.Stat(jar); err != nil {
		falhar("O arquivo "+nomeJar+" não foi encontrado.",
			"Ele precisa ficar na mesma pasta do executável:\n"+pasta+
				"\n\nCopie a pasta do programa inteira (com telemetria.jar e a pasta lib) e tente de novo.")
	}

	j, encontrados := procurarJava()
	if j == nil {
		detalhe := "Nenhuma instalação do Java foi encontrada."
		if len(encontrados) > 0 {
			detalhe = "Encontrado, mas antigo demais:\n" + strings.Join(encontrados, "\n")
		}
		falhar(fmt.Sprintf("É preciso ter o Java %d ou mais novo instalado.", javaMinimo),
			detalhe+fmt.Sprintf("\n\nInstale o Java %d+ (por exemplo, o Eclipse Temurin em adoptium.net) "+
				"e abra o programa de novo. Se o Java já estiver instalado em outra pasta, "+
				"defina a variável de ambiente JAVA_HOME apontando para ela.", javaMinimo))
	}

	argumentos := append([]string{"-Dfile.encoding=UTF-8", "-jar", jar}, argumentosDoPrograma()...)
	os.Exit(executar(j, pasta, argumentos))
}

func pastaDoExecutavel() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if real, err := filepath.EvalSymlinks(exe); err == nil {
		exe = real
	}
	return filepath.Dir(exe), nil
}

// ------------------------------------------------------------------ procurar o Java

// procurarJava devolve o primeiro Java >= javaMinimo, na ordem: JAVA_HOME, PATH e pastas
// de instalação conhecidas (a mais nova primeiro). Também devolve os antigos demais que
// encontrou, para a mensagem de erro.
func procurarJava() (*java, []string) {
	var antigos []string
	vistos := map[string]bool{}
	for _, bin := range candidatos() {
		limpo := strings.ToLower(filepath.Clean(bin))
		if vistos[limpo] {
			continue
		}
		vistos[limpo] = true
		j := javaEm(bin)
		if j == nil {
			continue
		}
		if j.versao >= javaMinimo {
			return j, antigos
		}
		antigos = append(antigos, fmt.Sprintf("  Java %d em %s", j.versao, bin))
	}
	return nil, antigos
}

// candidatos lista pastas "bin" que podem conter o Java.
func candidatos() []string {
	var bins []string
	if home := os.Getenv("JAVA_HOME"); home != "" {
		bins = append(bins, filepath.Join(home, "bin"))
	}
	if p, err := exec.LookPath(executavel("java")); err == nil {
		bins = append(bins, filepath.Dir(p))
	}
	bins = append(bins, instalacoesConhecidas()...)
	return bins
}

// instalacoesConhecidas varre as pastas onde os instaladores de Java costumam ficar,
// das versões mais novas para as mais antigas (pelo número no nome da pasta).
func instalacoesConhecidas() []string {
	var raizes []string
	if runtime.GOOS == "windows" {
		fornecedores := []string{"Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "Zulu",
			"BellSoft", "Amazon Corretto", "Semeru", "OpenJDK", "AdoptOpenJDK", "RedHat"}
		for _, pf := range []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramW6432"), os.Getenv("ProgramFiles(x86)")} {
			if pf == "" {
				continue
			}
			for _, f := range fornecedores {
				raizes = append(raizes, filepath.Join(pf, f))
			}
		}
		if home, err := os.UserHomeDir(); err == nil {
			raizes = append(raizes, filepath.Join(home, ".jdks")) // JDKs baixados pelo IntelliJ
		}
	} else {
		raizes = append(raizes, "/usr/lib/jvm", "/opt/java", "/Library/Java/JavaVirtualMachines")
	}

	var bins []string
	for _, raiz := range raizes {
		pastas, err := os.ReadDir(raiz)
		if err != nil {
			continue
		}
		sort.Slice(pastas, func(a, b int) bool { return numeroNoNome(pastas[a].Name()) > numeroNoNome(pastas[b].Name()) })
		for _, p := range pastas {
			if p.IsDir() {
				base := filepath.Join(raiz, p.Name())
				bins = append(bins, filepath.Join(base, "bin"), filepath.Join(base, "Contents", "Home", "bin"))
			}
		}
	}
	return bins
}

var reNumero = regexp.MustCompile(`\d+`)

func numeroNoNome(nome string) int {
	n, _ := strconv.Atoi(reNumero.FindString(nome))
	if n == 1 { // "jdk1.8.0_..." → 8
		if m := regexp.MustCompile(`1\.(\d+)`).FindStringSubmatch(nome); m != nil {
			n, _ = strconv.Atoi(m[1])
		}
	}
	return n
}

// javaEm confere se a pasta tem um Java que funciona e descobre a versão.
func javaEm(bin string) *java {
	console := filepath.Join(bin, executavel("java"))
	if _, err := os.Stat(console); err != nil {
		return nil
	}
	janela := filepath.Join(bin, executavel("javaw"))
	if _, err := os.Stat(janela); err != nil {
		janela = console // fora do Windows não existe javaw
	}
	cmd := exec.Command(console, "-version")
	esconderJanela(cmd)
	saida, err := cmd.CombinedOutput()
	if err != nil {
		return nil
	}
	v := versaoPrincipal(string(saida))
	if v == 0 {
		return nil
	}
	return &java{console: console, janela: janela, versao: v}
}

var reVersao = regexp.MustCompile(`version "(\d+)(?:\.(\d+))?`)

// versaoPrincipal lê a saída de "java -version": "17.0.9" → 17, "1.8.0_391" → 8.
func versaoPrincipal(saida string) int {
	m := reVersao.FindStringSubmatch(saida)
	if m == nil {
		return 0
	}
	v, _ := strconv.Atoi(m[1])
	if v == 1 && m[2] != "" {
		v, _ = strconv.Atoi(m[2])
	}
	return v
}

func executavel(nome string) string {
	if runtime.GOOS == "windows" {
		return nome + ".exe"
	}
	return nome
}

// ------------------------------------------------------------------ executar

// guardarFinal mantém só as últimas linhas da saída de erro, para mostrar se o Java falhar.
type guardarFinal struct{ linhas []string }

func (g *guardarFinal) Write(p []byte) (int, error) {
	s := bufio.NewScanner(bytes.NewReader(p))
	for s.Scan() {
		g.linhas = append(g.linhas, s.Text())
		if len(g.linhas) > 15 {
			g.linhas = g.linhas[1:]
		}
	}
	return len(p), nil
}

func detalheDoErro(err error, g *guardarFinal) string {
	var saida *exec.ExitError
	if errors.As(err, &saida) && len(g.linhas) > 0 {
		return strings.Join(g.linhas, "\n")
	}
	return err.Error()
}
