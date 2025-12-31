// Enable SPA routing support - redirect all routes to index.html
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
