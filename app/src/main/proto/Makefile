
#This is supposed to run under powershell. run with C:\ProgramData\chocolatey\bin\make.exe OR run "where.exe make" to find location of make exec

default:
		@echo "Generating protobuf compiled files for SignalService.proto..."
		@powershell If (Test-Path "D:\unfacd\android\dev\src\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java.old") {remove-item D:\unfacd\android\dev\src\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java.old}
		@powershell If (Test-Path "D:\unfacd\android\dev\src\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java") {rename-item D:\unfacd\android\dev\src\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java -newname D:\unfacd\android\dev\src\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java.old}
		@protoc.exe --java_out=java SignalService.proto
		@echo "Copying file to source repo..."
		@powershell copy-item D:\unfacd\android\dev\protobuf\java\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java -Destination D:\unfacd\android\dev\src\org\whispersystems\signalservice\internal\push\SignalServiceProtos.java
		
all:
	protoc.exe --java_out=java WebRtcData.proto Backups.proto DeviceName.proto
