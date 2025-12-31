// Allow connections from external hosts (VPN, network IP, etc.)
config.devServer = config.devServer || {};
config.devServer.allowedHosts = 'all';
config.devServer.host = '0.0.0.0';
