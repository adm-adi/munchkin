const path = require('path');
const winston = require('winston');
require('winston-daily-rotate-file');

// Resolved against this file, not the process CWD: starting the server from a
// different directory used to scatter logs into whatever directory it was launched
// from. Same footgun that applied to the database path.
const LOG_DIR = process.env.MUNCHKIN_LOG_DIR || path.join(__dirname, 'logs');

const errorRotateTransport = new winston.transports.DailyRotateFile({
    filename: path.join(LOG_DIR, 'error-%DATE%.log'),
    datePattern: 'YYYY-MM-DD',
    zippedArchive: true,
    maxSize: '20m',
    maxFiles: '14d',
    level: 'error'
});

const combinedRotateTransport = new winston.transports.DailyRotateFile({
    filename: path.join(LOG_DIR, 'combined-%DATE%.log'),
    datePattern: 'YYYY-MM-DD',
    zippedArchive: true,
    maxSize: '20m',
    maxFiles: '14d'
});

const logger = winston.createLogger({
    level: 'info',
    format: winston.format.combine(
        winston.format.errors({ stack: true }),
        winston.format.timestamp(),
        winston.format.json()
    ),
    transports: [
        errorRotateTransport,
        combinedRotateTransport,
        new winston.transports.Console({
            format: winston.format.combine(
                winston.format.colorize(),
                winston.format.errors({ stack: true }),
                winston.format.simple()
            )
        })
    ],
});

module.exports = logger;
