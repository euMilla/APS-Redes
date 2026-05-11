# APS Redes

Sistema cliente-servidor em Java 17 para chat em rede, canais de equipe, envio de relatorios, painel admin, alertas multicast UDP e webcam.

## Como Rodar Depois de Baixar do GitHub

1. Instale o `Java JDK 17+`.
2. Instale o `Maven`.
3. Execute `instalar-aplicacao.bat`.
4. Execute `iniciar-aplicacao.bat`.

A pasta `lib\` nao fica no Git porque e muito grande. O script `instalar-aplicacao.bat`
baixa as dependencias pelo `pom.xml`, compila o projeto e recria a pasta `lib\`
automaticamente.

## Conformidade APS

Este projeto atende ao enunciado `DESENVOLVIMENTO DE UMA FERRAMENTA PARA COMUNICACAO EM REDE`:

- Linguagem: `Java`
- Comunicacao em rede entre `2+ pessoas`: cliente-servidor com multiplos clientes simultaneos
- Protocolo: `TCP/IP`
- Primitivas de rede (Berkeley sockets ou derivadas):
  - `java.net.ServerSocket` e `java.net.Socket` para chat/login/controle
  - `java.net.ServerSocket` e `java.net.Socket` para transferencia de arquivos
  - `java.net.MulticastSocket` para alertas multicast
- Caso de uso da Secretaria / Rio Tiete:
  - Fluxo de inspetores com canais, chat online e relatorios
  - Painel administrativo para acompanhamento central
  - Registro e auditoria de eventos relevantes

Recursos extras implementados (que aumentam complexidade):

- Componentes graficos (JavaFX)
- Transferencia de arquivos/relatorios
- Comunicacao multicast UDP
- Webcam (captura e envio de imagem)

## Requisitos

- Java JDK 17 ou superior
- Maven 3.9 ou superior
- MySQL 8 opcional

Verifique no Windows:

```powershell
java -version
javac -version
mvn -version
```

## Instalacao Rapida

1. Execute `instalar-aplicacao.bat`.
2. Opcional: execute `setup-mysql.bat` para criar o banco `aps_redes`.
3. Execute `iniciar-aplicacao.bat`.

O script abre o servidor e dois clientes de teste. Para simular mais usuarios, execute `run-client.bat nome-do-usuario`.

## Configuracao

Os arquivos principais ficam em `config\`:

- `config\app.properties`: portas, pasta de dados e senhas iniciais.
- `config\database.properties`: MySQL.

O arquivo `.env` ainda e aceito somente como compatibilidade quando a pasta `config` nao existe.

## MySQL

Para criar o banco automaticamente:

```text
setup-mysql.bat
```

O script executa `database\schema.sql`, cria as tabelas e grava `config\database.properties` com `DB_ENABLED=true`.

Tabelas criadas:

- `aps_auth`: hashes PBKDF2 das senhas.
- `aps_user_access`: cargos e canais permitidos.
- `aps_reports`: indice dos relatorios enviados.
- `aps_audit_logs`: auditoria de login, upload, permissao, senha e banco.

Se `DB_ENABLED=false`, o sistema funciona em modo local usando `server-storage`.

## Acessos Demo

Senhas iniciais:

- Membro: `membro123`
- Admin: `admin123`

Usuarios demo para digitar no campo de nome:

- `membro-demo`: acesso a `Equipe Alfa`.
- `inspetor-demo`: acesso a `Equipe Alfa` e `Equipe Beta`.
- `coordenador-demo`: acesso a todos os canais como membro coordenador.

Entre como admin para alterar cargos, canais e senhas. Apenas admin altera senhas do sistema.

## Execucao Manual

Compile:

```text
build.bat
```

Abra o servidor:

```text
run-server.bat
```

Abra o cliente:

```text
run-client.bat
```

Abra dois clientes de teste com nomes diferentes:

```text
run-client.bat membro-demo
run-client.bat inspetor-demo
```

## Dependencias JAR

O build centraliza os arquivos `.jar` na pasta `lib\`. Os scripts `run-client.bat`,
`run-server.bat` e `criar-pacote-instalavel.bat` usam essa pasta para executar e
empacotar a aplicacao.

Portas padrao:

- Chat TCP: `5050`
- Arquivos TCP: `5051`
- Multicast UDP: `230.0.0.7:4446`

## Relatorios e Uploads

Os uploads aceitam `txt`, `pdf`, `doc`, `docx`, `csv`, `log` e `md`, com limite de 100 MB. Membros veem relatorios do canal atual. Admin ve relatorios de todos os canais na aba `Relatorios`.

## Logs

Arquivos gerados em execucao:

- `server-storage\logs\aps-system.log`: log tecnico do servidor.
- `server-storage\logs\audit.log`: auditoria funcional.
- `server-storage\downloads`: downloads/previews feitos pelo cliente.

Essas pastas sao runtime e nao precisam ir no pacote final.

## Pacote Instalavel

Para gerar um ZIP portatil:

```text
criar-pacote-instalavel.bat
```

O arquivo sai em `dist\APS-Redes-instalavel.zip`.

## Comandos do Servidor

```text
/alert mensagem
/critical mensagem critica
/users
/quit
```

Texto livre no console do servidor e enviado como alerta TCP para os clientes.
