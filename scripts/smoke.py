#!/usr/bin/env python3
"""Run all four packaged services with ephemeral credentials and verify the gateway."""
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

ports = {name: port() for name in ('discovery-server', 'hotel-service', 'booking-service', 'api-gateway')}
logs = Path(tempfile.mkdtemp(prefix='stayflow-smoke-'))
print('Logs: ' + str(logs), flush=True)
processes = []
handles = []
base_env = dict(os.environ, JWT_SECRET=secrets.token_urlsafe(48),
                INTERNAL_JWT_SECRET=secrets.token_urlsafe(48),
                EUREKA_USERNAME='registry', EUREKA_PASSWORD=secrets.token_urlsafe(24),
                DEMO_ADMIN_PASSWORD=secrets.token_urlsafe(20), DEMO_USER_PASSWORD=secrets.token_urlsafe(20))
base_env['EUREKA_URL'] = f"http://{base_env['EUREKA_USERNAME']}:{base_env['EUREKA_PASSWORD']}@127.0.0.1:{ports['discovery-server']}/eureka/"

def request(base, method, path, body=None, token=None, key=None, expected=200):
    headers = {'Content-Type': 'application/json', 'X-Trace-Id': 'smoke-trace'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status, raw = response.status, response.read()
            assert response.headers.get('X-Trace-Id') == 'smoke-trace' or '/actuator/' in path
    except urllib.error.HTTPError as response:
        status, raw = response.code, response.read()
    assert status == expected, (method, path, status, raw.decode()[:400])
    return json.loads(raw) if raw else None

def wait_health(name):
    url = f'http://127.0.0.1:{ports[name]}/actuator/health'
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if processes[-1].poll() is not None:
            raise RuntimeError(f'{name} exited; inspect {logs}')
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(.5)
    raise TimeoutError(f'{name} health timeout; inspect {logs}')

try:
    for name in ports:
        jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
        assert len(jars) == 1, f'Package {name} with ./mvnw verify first'
        env = dict(base_env, SERVER_PORT=str(ports[name]))
        if name in ('hotel-service', 'booking-service'):
            env['SPRING_PROFILES_ACTIVE'] = 'demo'
        output = open(logs / (name + '.log'), 'w')
        handles.append(output)
        processes.append(subprocess.Popen(['java', '-Xmx192m', '-jar', str(jars[0])], cwd=ROOT, env=env,
                                          stdout=output, stderr=subprocess.STDOUT))
        wait_health(name)
        print(name + ': healthy', flush=True)
    gateway = f"http://127.0.0.1:{ports['api-gateway']}"
    deadline = time.monotonic() + 60
    while True:
        try:
            admin = request(gateway, 'POST', '/user/auth', {'username': 'admin', 'password': base_env['DEMO_ADMIN_PASSWORD']})['accessToken']
            break
        except AssertionError:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)
    user = request(gateway, 'POST', '/user/register', {'username': 'smokeuser', 'password': secrets.token_urlsafe(20)})['accessToken']
    stranger = request(gateway, 'POST', '/user/register', {'username': 'stranger', 'password': secrets.token_urlsafe(20)})['accessToken']
    hotel = request(gateway, 'POST', '/api/hotels', {'name': 'Smoke Hotel', 'address': 'Smoke Avenue'}, admin)
    room = request(gateway, 'POST', '/api/rooms', {'hotelId': hotel['id'], 'number': 'S01', 'available': True}, admin)
    start = dt.date.today() + dt.timedelta(days=2)
    body = {'roomId': room['id'], 'autoSelect': False, 'startDate': str(start), 'endDate': str(start + dt.timedelta(days=2))}
    booking = request(gateway, 'POST', '/api/bookings', body, user, 'smoke-booking', 201)
    assert booking['status'] == 'CONFIRMED'
    alias = request(gateway, 'GET', '/api/bookings/' + booking['id'], token=user)
    assert alias['id'] == booking['id']
    alias_history = request(gateway, 'GET', '/api/bookings?page=0&size=5', token=user)
    assert alias_history['totalElements'] == 1
    recommendations = request(gateway, 'GET', f'/api/bookings/recommend?startDate={start}&endDate={start + dt.timedelta(days=2)}', token=user)
    assert recommendations and all(r['available'] for r in recommendations)
    assert recommendations == sorted(recommendations, key=lambda r: (r['timesBooked'], r['id']))
    default_rooms = request(gateway, 'GET', '/api/rooms', token=user)
    assert all(r['available'] for r in default_rooms)
    assert [r['id'] for r in default_rooms] == sorted(r['id'] for r in default_rooms)

    replay = request(gateway, 'POST', '/booking', body, user, 'smoke-booking', 201)
    assert replay['id'] == booking['id']
    request(gateway, 'POST', '/booking', dict(body, roomId=999), user, 'smoke-booking', 409)
    request(gateway, 'POST', '/booking', body, user, 'smoke-overlap', 409)
    request(gateway, 'GET', '/booking/' + booking['id'], token=stranger, expected=404)
    history = request(gateway, 'GET', '/bookings?page=0&size=5', token=user)
    assert history['totalElements'] == 2
    stats = request(gateway, 'GET', f'/api/hotels/statistics?startDate={start}&endDate={start + dt.timedelta(days=2)}', token=admin)
    assert stats['occupiedRoomNights'] >= 2 and stats['occupancyRate'] > 0
    auto = request(gateway, 'POST', '/booking', dict(body, autoSelect=True, roomId=999), user, 'smoke-auto', 201)
    assert auto['roomId'] != 999
    for role_token in (user, admin):
        request(gateway, 'POST', f"/api/rooms/{room['id']}/release", {}, role_token, expected=403)
        request(f"http://127.0.0.1:{ports['hotel-service']}", 'POST', f"/api/rooms/{room['id']}/confirm-availability", {'bookingId': 'forbidden', 'requestId': 'forbidden', 'startDate': str(start), 'endDate': str(start + dt.timedelta(days=1))}, role_token, expected=403)
    request(gateway, 'GET', '/api/hotels', expected=401)
    request(gateway, 'GET', '/bookings', token='invalid', expected=401)
    request(gateway, 'POST', '/user/register', {'username': 'attack', 'password': secrets.token_urlsafe(20), 'role': 'ADMIN'}, expected=400)
    request(gateway, 'DELETE', '/booking/' + booking['id'], token=user)
    request(gateway, 'DELETE', '/booking/' + booking['id'], token=user)
    request(gateway, 'DELETE', '/booking/' + auto['id'], token=user)
    hotel_base = f"http://127.0.0.1:{ports['hotel-service']}"
    booking_base = f"http://127.0.0.1:{ports['booking-service']}"
    public_spec = request(hotel_base, 'GET', '/v3/api-docs/public')
    internal_spec = request(hotel_base, 'GET', '/v3/api-docs/internal')
    booking_spec = request(booking_base, 'GET', '/v3/api-docs/public')
    assert '/api/rooms/{id}/release' not in public_spec['paths']
    assert '/api/rooms/{id}/release' in internal_spec['paths']
    assert 'ErrorDTO' in public_spec['components']['schemas']
    assert '201' in booking_spec['paths']['/booking']['post']['responses']
    for log in logs.glob('*.log'):
        assert base_env['EUREKA_PASSWORD'] not in log.read_text(), 'Registry credentials leaked to logs'
    print('SMOKE PASSED: discovery, auth, CRUD, saga, idempotency, conflicts, ownership, statistics, cancellation, internal isolation', flush=True)
    print('Logs: ' + str(logs), flush=True)
finally:
    for process in reversed(processes):
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    for handle in handles:
        handle.close()
