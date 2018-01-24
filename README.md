# Trying it out

* Run all unit tests. Some flaky timeouts are possible, unfortunately.
* Run `ShowPeersForTorrentApp` and pass path to some `.torrent` file as a parameter. For example, you can download [Debian ISO torrent](http://mirror.yandex.ru/debian-cd/current/amd64/bt-cd/) and the app will print you list of all peers reported by the tracker, as well as what pieces peers report to have.
* Run `DownloaderTorrentApp`, pass path to some `.torrent` file (it should contain a single file) and an output file. The program should save the torrent into the file. Note that it may be significantly slower than your standard client.
