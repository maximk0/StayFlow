# StayFlow

REST API для управления отелями, номерами и бронированиями. Поддерживает регистрацию, JWT-авторизацию, роли USER/ADMIN, автоматический подбор свободного номера и отмену брони. Бронирование согласуется между сервисами через сагу с компенсацией.

Стек: Java 17+, Spring Boot 3.5.15, Spring Cloud 2025.0.3, Spring Security, Spring Data JPA, H2 и Resilience4j. Сборка через Maven Wrapper.

| Модуль | Назначение | Порт |
| --- | --- | --- |
| discovery-server | Реестр Eureka | 8761 |
| api-gateway | Входной API | 8080 |
| hotel-service | Отели, номера, доступность и статистика | 8081 |
| booking-service | Пользователи, авторизация и бронирования | 8082 |

У Hotel и Booking отдельные базы H2 в памяти. После остановки сервисов данные теряются. `stayflow-common` содержит общие классы ошибок и логирования.

## Запуск

Нужен JDK 17 или новее. Maven устанавливать отдельно не требуется; для первой сборки нужен доступ к Maven Central. Команды ниже рассчитаны на macOS/Linux.

1. Из корня проекта выполните сборку и подготовьте настройки:

   ```sh
   ./mvnw verify
   cp .env.example .env
   ```

   Если `.env` уже настроен, повторно копировать его не нужно.

2. Замените значения `REPLACE_WITH_...` в `.env`. Для двух разных JWT-секретов дважды выполните `openssl rand -hex 32`. Задайте пароль Eureka и пароли demo-пользователей длиной 10-72 UTF-8 байта. Файл `.env` исключён из Git.

3. Откройте четыре терминала в корне проекта. В каждом загрузите настройки:

   ```sh
   set -a
   . ./.env
   set +a
   ```

4. Запустите сервисы по порядку, каждый в своём терминале:

   ```sh
   # Терминал 1
   java -jar discovery-server/target/discovery-server-1.0.0-SNAPSHOT.jar
   ```

   ```sh
   # Терминал 2
   java -jar hotel-service/target/hotel-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=demo
   ```

   ```sh
   # Терминал 3
   java -jar booking-service/target/booking-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=demo
   ```

   ```sh
   # Терминал 4
   java -jar api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
   ```

Профиль `demo` добавляет отели, номера и пользователей `admin` и `demo`. Их пароли берутся из `DEMO_ADMIN_PASSWORD` и `DEMO_USER_PASSWORD`. Без этого профиля базы пустые, готового администратора нет.

Дождитесь регистрации сервисов в Eureka. Проверить готовность можно по `/actuator/health` на порту каждого приложения; Booking может возвращать 503, пока не обнаружит Hotel. Для остановки нажмите `Ctrl+C` в каждом терминале.

## Примеры запросов

Адрес API: `http://localhost:8080`. Пользовательского веб-интерфейса нет. Защищённые запросы требуют JWT; открытие адреса без токена возвращает 401.

### Регистрация и вход

```sh
curl -s http://localhost:8080/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"traveler","password":"change-this-password"}'

curl -s http://localhost:8080/user/auth \
  -H 'Content-Type: application/json' \
  -d '{"username":"traveler","password":"change-this-password"}'
```

Скопируйте `accessToken` из ответа в переменную:

```sh
TOKEN='вставьте accessToken'
```

Токен действует один час. Публичная регистрация создаёт только USER. Для административных запросов войдите как `admin` с паролем из `.env` и используйте его токен.

### Отели и свободные номера

```sh
curl -s http://localhost:8080/api/hotels \
  -H "Authorization: Bearer $TOKEN"

curl -s 'http://localhost:8080/api/rooms?page=0&size=10' \
  -H "Authorization: Bearer $TOKEN"
```

Без дат список номеров учитывает эксплуатационную доступность. Для проверки занятости передайте `startDate` и `endDate`; замените значения ниже будущими датами в формате `YYYY-MM-DD`:

```sh
START_DATE='YYYY-MM-DD'
END_DATE='YYYY-MM-DD'

curl -s "http://localhost:8080/api/rooms?startDate=$START_DATE&endDate=$END_DATE" \
  -H "Authorization: Bearer $TOKEN"

curl -s "http://localhost:8080/api/bookings/recommend?startDate=$START_DATE&endDate=$END_DATE" \
  -H "Authorization: Bearer $TOKEN"
```

Рекомендации отсортированы по числу бронирований, затем по ID номера. Интервалы трактуются как `[startDate, endDate)`: выезд и следующий заезд в один день совместимы.

### Создание, просмотр и отмена брони

```sh
curl -s http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: trip-001' \
  -d "{\"autoSelect\":true,\"startDate\":\"$START_DATE\",\"endDate\":\"$END_DATE\"}"

curl -s 'http://localhost:8080/api/bookings?page=0&size=20' \
  -H "Authorization: Bearer $TOKEN"

BOOKING_ID='вставьте id из ответа создания'

curl -s "http://localhost:8080/api/bookings/$BOOKING_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -s -X DELETE "http://localhost:8080/api/bookings/$BOOKING_ID" \
  -H "Authorization: Bearer $TOKEN"
```

Для выбора конкретного номера передайте `autoSelect: false` и `roomId`. При автоподборе `roomId` игнорируется. Повтор одного запроса выполняйте с тем же `Idempotency-Key`; для новой брони используйте новый ключ. Тот же ключ с другим телом возвращает 409. Пользователь видит и отменяет только свои брони. Изменение дат выполняется через отмену и новое бронирование.

Также доступны исходные маршруты: `POST /booking`, `GET /bookings`, `GET/DELETE /booking/{id}`.

### Административные запросы

С токеном ADMIN можно создать отель:

```sh
curl -s http://localhost:8080/api/hotels \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Central Hotel","address":"Main Street, 10"}'
```

И получить загрузку за выбранный период:

```sh
curl -s "http://localhost:8080/api/hotels/statistics?startDate=$START_DATE&endDate=$END_DATE" \
  -H "Authorization: Bearer $TOKEN"
```

Ответ содержит занятые ночи (`occupiedRoomNights`), доступную ёмкость (`capacityRoomNights`) и их отношение (`occupancyRate`). Ёмкость равна числу действующих эксплуатационно доступных номеров на момент запроса, умноженному на число ночей периода. Недоступные и удалённые номера исключаются; при нулевой ёмкости доля загрузки равна 0.

Остальные операции с отелями, номерами и пользователями доступны в Swagger:

- [Booking Swagger](http://localhost:8082/swagger-ui/index.html), спецификация `public`.
- [Hotel Swagger](http://localhost:8081/swagger-ui/index.html), спецификация `public`.

Swagger обращается напрямую к сервисам. Спецификация Hotel `internal` описывает межсервисные confirm/release; через Gateway эти операции запрещены.

## Проверка

```sh
./mvnw verify
python3 scripts/smoke.py
```

`verify` выполняет тесты, проверку форматирования и сборку JAR. Для smoke нужен Python 3 без дополнительных пакетов: скрипт запускает четыре сервиса на свободных портах, проверяет API через Gateway и останавливает процессы.
