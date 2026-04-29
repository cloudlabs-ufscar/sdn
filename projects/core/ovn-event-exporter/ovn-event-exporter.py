#!/usr/bin/env python3

import logging
import signal
import sys
import time
import threading
import argparse

import prometheus_client as pclient
import socket
from ovs.db import idl
from ovsdbapp.backend.ovs_idl import idlutils
from ovsdbapp.backend.ovs_idl import connection
from ovsdbapp.schema.open_vswitch import impl_idl as ovs_impl_idl
from wsgiref.simple_server import make_server, WSGIServer, WSGIRequestHandler
from socketserver import ThreadingMixIn


SBDB_SCHEMA_NAME = 'OVN_Southbound'
NBDB_SCHEMA_NAME = 'OVN_Northbound'
CLUSTERED = "clustered"
RELAY = "relay"
signal_interupt = False

logging.basicConfig(format='%(asctime)s %(levelname)s %(message)s',
                    level=logging.INFO)
logger = logging.getLogger()

events_counter = None
leader = False

pclient.REGISTRY.unregister(pclient.GC_COLLECTOR)
pclient.REGISTRY.unregister(pclient.PLATFORM_COLLECTOR)
pclient.REGISTRY.unregister(pclient.PROCESS_COLLECTOR)

parser = argparse.ArgumentParser(description='Export OVN events from OVN '
                                 'SouthBound Database')
parser.add_argument('--sbdb', required=False, help="OVN Southbound OVSDB "
                    "connection string")
parser.add_argument('--nbdb', required=False, help="OVN Northbound OVSDB "
                    "connection string")
parser.add_argument('--bind_port', type=int, default=9000, help="Metrics exposing "
                    "TCP port")
parser.add_argument('--bind_address', type=str, default='0.0.0.0', help="Metrics exposing "
                    "IP address")
parser.add_argument('--timeout', type=int, default=30, help="OVS DB connection "
                    "timeout")
parser.add_argument('--debug', default=False, action='store_true', help="Set "
                    "logging level to DEBUG")


class NotificationBackend(object):

    def __init__(self, schema_name):
        self.schema_name = 'southbound'
        if schema_name == NBDB_SCHEMA_NAME:
            self.schema_name = 'northbound'

    def notify_event(self, event, row):
        global events_counter
        table_name = row._table.name
        logger.debug('IDL New event "%s", table "%s"' % (event, table_name))
        events_counter.labels(self.schema_name, table_name, event).inc()


class OvsIdl(idl.Idl):

    def __init__(self, connection_string, schema_name, notification_backend):
        helper = idlutils.get_schema_helper(connection_string, schema_name)
        helper.register_all()
        self._notification_backend = notification_backend
        super(OvsIdl, self).__init__(connection_string, helper)

    def notify(self, event, row, updates=None):
        self._notification_backend.notify_event(event, row)

def is_leader(idl):
    if idl._server_db_table not in idl.server_tables:
        return false
    rows = idl.server_tables[idl._server_db_table].rows

    database = None
    for row in rows.values():
        if idl.cluster_id:
            if idl.cluster_id in \
                map(lambda x: str(x)[:4], row.cid):
                    database = row
                    break
        elif row.name == idl._db.name:
            database = row
            break

    if not database:
        logger.info("Server does not have %s database"
                  % (idl._db.name))
        return False

    if not (database.model == CLUSTERED or database.model == RELAY):
        return True

    if not database.schema:
        return False
    if not database.connected:
        return False
    if not database.leader:
        return False
    return True

def get_ovs(viewer, schema, connection_string, ovsdb_timeout):
    idl = OvsIdl(connection_string, schema, viewer)
    idl.leader_only = False
    _conn = connection.Connection(idl, timeout=ovsdb_timeout)
    return ovs_impl_idl.OvsdbIdl(_conn, start=True)

def check_leader(idl):
    global leader
    leader = is_leader(idl)
    timer = threading.Timer(60, check_leader, [idl])
    timer.start()
    return timer


class ThreadingWSGIServer(ThreadingMixIn, WSGIServer):
    """Thread per request HTTP server."""
    daemon_threads = True

class _SilentHandler(WSGIRequestHandler):
    """WSGI handler that does not log requests."""
    def log_message(self, format, *args):
        """Log nothing."""

def _get_best_family(address, port):
    """Automatically select address family depending on address"""
    infos = socket.getaddrinfo(address, port)
    family, _, _, _, sockaddr = next(iter(infos))
    return family, sockaddr[0]

def null_app(environ, start_response):
    start_response("200 OK", [("Content-Type","text/plain")])
    return [b'### I wish I could be a leader\n']

def start_http_server(app, port, addr):
    """Starts a WSGI server for prometheus metrics as a daemon thread."""

    class TmpServer(ThreadingWSGIServer):
        """Copy of ThreadingWSGIServer to update address_family locally"""

    TmpServer.address_family, addr = _get_best_family(addr, port)
    httpd = make_server(addr, port, app, TmpServer, handler_class=_SilentHandler)
    t = threading.Thread(target=httpd.serve_forever)
    t.daemon = True
    t.start()
    return httpd

def signal_handler(signum, frame):
    global signal_interupt
    signal_interupt = True

if __name__ == "__main__":
    args = parser.parse_args()

    if args.debug:
        logger.setLevel(logging.DEBUG)
        logger.debug("Logging level set to DEBUG")

    logger.debug('Provided arguments: %s' % args)

    if not bool(args.sbdb) ^ bool(args.nbdb):
        logger.error('Only NBDB or SBDB usage possible. --nbdb and --sbdb '
                     'parameter cannot be use together')
        sys.exit(1)

    if args.sbdb:
        schema = SBDB_SCHEMA_NAME
        db_connection_string = args.sbdb
    else:
        schema = NBDB_SCHEMA_NAME
        db_connection_string = args.nbdb

    events_counter = pclient.Counter("ovn_events", "OVN events counter",
                                     ['schema', 'table', 'event'])
    signal.signal(signal.SIGTERM, signal_handler)
    signal.signal(signal.SIGINT, signal_handler)

    try:
        ovs = get_ovs(NotificationBackend(schema), schema, db_connection_string,
                      args.timeout)
    except Exception as e:
        logger.error('Cannot connect to OVS DB. Check parameters: %s' % e)
        sys.exit(1)

    timer = check_leader(ovs.idl)
    app = pclient.make_wsgi_app(pclient.REGISTRY)
    httpd = start_http_server(app, args.bind_port, args.bind_address)

    while not signal_interupt:
        # period between collection
        time.sleep(1)
        if leader and httpd.get_app() == null_app:
            httpd.set_app(app)
        elif not leader and httpd.get_app() == app:
            httpd.set_app(null_app)

    timer.cancel()
    httpd.shutdown()
    ovs.connection.stop()