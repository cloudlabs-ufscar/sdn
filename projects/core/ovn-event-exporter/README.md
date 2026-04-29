# OVN prometheus event exporter

OVN table events prometheus exporter.

## Functionality

Allows to monitor OVN Southbound and Northbound DB activity.

## Installation

### standalone

Create python environment and install proper requirements:

    cd ./ovn-event-exporter
    python3.9 -m venv ./venv
    . ./venv/bin/activate
    pip install -r requirements.txt
    ./venv/bin/python ovn-event-exporter.py --sbdb tcp:0.0.0.0:6642 --bind_port 8888 --timeout 60

### Docker image

Use attached Docker file to create Docker image

    cd ./ovn-event-exporter
    docker build -t ovn-event-exporter:v1.0 .
    docker run -u root ovn-event-exporter:v1.0 /usr/sbin/ovn-event-exporter.py --h
    docker run -p 8888:8888  ovn-event-exporter:v1.0 /usr/sbin/ovn-event-exporter.py --sbdb tcp:0.0.0.0:6642 --bind_port 8888 --timeout 60

## Running

Help is contained in the exporter command.

    $ ./ovn-event-exporter.py -h
    usage: ovn-event-exporter.py [-h] [--sbdb SBDB] [--nbdb NBDB] [--bind_port BIND_PORT] [--bind_address BIND_ADDRESS] [--timeout TIMEOUT]
    
    Export OVN events from OVN SouthBound Database
    
    optional arguments:
      -h, --help            show this help message and exit
      --sbdb SBDB           OVN Southbound OVSDB connection string
      --nbdb NBDB           OVN Northbound OVSDB connection string
      --bind_port BIND_PORT
                            Metrics exposing TCP port
      --bind_address BIND_ADDRESS
                            Metrics exposing IP address
      --timeout TIMEOUT     OVS DB connection timeout
