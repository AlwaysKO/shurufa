param(
    [int]$ListenPort = 3001,
    [Parameter(Mandatory = $true)][string]$TargetHost,
    [int]$TargetPort = 3000
)

$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

public static class ShurufaWslTcpRelay {
    public static async Task RunAsync(int listenPort, string targetHost, int targetPort) {
        var listener = new TcpListener(IPAddress.Loopback, listenPort);
        listener.Start();
        while (true) {
            var client = await listener.AcceptTcpClientAsync();
            Task ignored = HandleAsync(client, targetHost, targetPort);
        }
    }

    private static async Task HandleAsync(TcpClient client, string targetHost, int targetPort) {
        using (client)
        using (var upstream = new TcpClient()) {
            try {
                await upstream.ConnectAsync(targetHost, targetPort);
                var clientStream = client.GetStream();
                var upstreamStream = upstream.GetStream();
                var toUpstream = clientStream.CopyToAsync(upstreamStream);
                var toClient = upstreamStream.CopyToAsync(clientStream);
                var first = await Task.WhenAny(toUpstream, toClient);
                if (first == toUpstream) {
                    try { upstream.Client.Shutdown(SocketShutdown.Send); } catch { }
                    await toClient;
                } else {
                    try { client.Client.Shutdown(SocketShutdown.Send); } catch { }
                    await toUpstream;
                }
            } catch { }
        }
    }
}
'@

[ShurufaWslTcpRelay]::RunAsync($ListenPort, $TargetHost, $TargetPort).GetAwaiter().GetResult()
