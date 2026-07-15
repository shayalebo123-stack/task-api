# LocalStack Walkthrough — run the Tasks API on Docker Desktop with a fake AWS

A **hands-on, follow-along lab**: start from a fresh clone, run the app, bring up
the whole system on Docker Desktop with **LocalStack** (a single container that
emulates AWS), and then call **every** API endpoint yourself. No AWS account, no
credit card, no cost.

Paste the commands in order — each one is real and each step says what it
*proves*. Where a command produces an id you'll need later, we capture it into a
shell variable so you can keep pasting without hunting for it.

> **How this differs from the other docs.** [demo.md](demo.md) is the
> *instructor's* demo script (with talking points) and also shows the **live AWS**
> deploy; [runbook.md](runbook.md) is operations. **This file is the student lab**
> and stays entirely on your laptop (Docker Desktop + LocalStack). When you're
> ready to compare against real AWS, jump to
> [demo.md → Part 2](demo.md#part-2--the-same-demo-against-the-live-aws-stack).

---

## 0. What you're building

The Tasks API is a plain-Java HTTP service. On a real deploy it stores tasks in
**DynamoDB** and, on every create, fires an event through **SQS → Lambda → SNS**.
LocalStack stands in for all of those AWS services so the *entire* system runs in
two containers on your machine:

```text
  Docker Desktop
  ┌──────────────────────────────────────────────────────────────────────┐
  │                                                                        │
  │   you ── curl ──▶  app  (container, port 8080)                         │
  │                     │                                                  │
  │                     │  AWS SDK, pointed at http://localstack:4566      │
  │                     ▼                                                  │
  │            LocalStack  (container, port 4566)  ── "fake AWS"           │
  │            ┌───────────────────────────────────────────────┐          │
  │            │  DynamoDB   SQS ──▶ Lambda ──▶ SNS   S3         │          │
  │            │  (tasks)    (task-events)   (task-notifications)│          │
  │            └───────────────────────────────────────────────┘          │
  └──────────────────────────────────────────────────────────────────────┘
```

**What is LocalStack?** One Docker image that speaks the real AWS APIs on a single
port (`4566`). The app's AWS SDK can't tell it apart from AWS — the *only*
difference is the endpoint URL. That's the whole point: the commands you learn
here (`awslocal dynamodb scan …`) are the exact commands you'd run against real
AWS, just pointed at localhost.

---

## 1. Prerequisites

| Need | Why | Check |
|---|---|---|
| **Docker Desktop — running** | Hosts both containers. | `docker ps` prints a table (no error) |
| **Java 17** (newer is fine) | Runs the app locally; builds the jars. | `java -version` |
| **Maven 3.9+** | Builds the app **and the Lambda jar**. | `mvn -version` |
| **curl** | Calls the API. | preinstalled on macOS/Linux |
| `jq` *(optional but used below)* | Pretty-prints JSON and lets us pull an `id` out of a response. | `jq --version` — or `brew install jq` |

Two things that trip students up:

- **Maven is required even for the Docker path.** `make compose-up` builds the
  Lambda jar **on your machine first**, then hands it to LocalStack. Without Maven
  the init script silently skips the Lambda
  ([localstack/init/02-deploy-lambda.sh](../localstack/init/02-deploy-lambda.sh)),
  the DynamoDB + SQS parts still work, but the **event chain never fires** and it
  looks broken. If you see `lambda jar not found … skipping Lambda deploy` in the
  logs, that's this.
- **You do *not* need the AWS CLI or `awslocal` installed.** `awslocal` (the AWS
  CLI pre-pointed at LocalStack) already lives *inside* the LocalStack container;
  we reach it with `docker exec`.

> **Apple Silicon (M-series) note:** the image targets `linux/amd64` (the same
> architecture as the course's EC2 box and CI), so on an M-chip Mac the **first**
> `compose-up` runs under emulation and is slow — several minutes. Reruns are
> cached and fast. This is deliberate: what you run locally is byte-identical to
> what ships to AWS.

Everything below runs from the **repository root**.

---

## 2. Step 1 — Run the app on its own (in-memory)

Before Docker or AWS, prove the plain app works. In this mode there's **no
database** — tasks live in an in-memory map (`STORAGE=memory`, the default), which
is exactly the "session 1" experience of the course.

```bash
make test      # compile + run the unit tests
make run       # build the fat jar, then run it on http://localhost:8080
```

Leave it running and, in a **second terminal**, hit the health endpoint:

```bash
curl -s http://localhost:8080/health
# {"status":"UP","version":"dev"}
```

**What this proves:** the service compiles, its tests pass, and it serves HTTP —
with zero AWS involved. `version` is `dev` here because nothing set `APP_VERSION`.

Stop it with **Ctrl-C** when you're done, because Step 2 needs port `8080`.

---

## 3. Step 2 — Bring up the full stack on Docker Desktop + LocalStack

Now the real thing. One command builds the Lambda jar, builds the app image, and
starts **both** containers wired together:

```bash
make compose-up
```

Under the hood this is
`mvn -pl lambda -am package` **then**
`docker compose -f docker/docker-compose.yml up --build`
(see the [Makefile](../Makefile) and
[docker/docker-compose.yml](../docker/docker-compose.yml)).

It runs in the **foreground** and streams both containers' logs. When LocalStack
becomes healthy, its init scripts create the DynamoDB table, the SQS queue (+
dead-letter queue), the SNS topic, and deploy the Lambda. **Wait for this line:**

```
[init] lambda deploy done — the full event chain is live.
```

Then, in a second terminal, confirm the app is up:

```bash
curl -s http://localhost:8080/health
# {"status":"UP","version":"local"}
```

**What this proves:** both containers are up and talking. Notice `version` is now
`local` (the compose file sets `APP_VERSION=local`) — a small tell that you're
hitting the *container*, not the Step-1 process.

### What just got wired (so the magic isn't magic)

The compose file connects the app to LocalStack purely through **environment
variables** — the app code has no idea it's talking to a fake:

| Variable | Value | Effect |
|---|---|---|
| `STORAGE` | `dynamodb` | Store tasks in DynamoDB instead of memory |
| `AWS_ENDPOINT_URL` | `http://localstack:4566` | **The one line that redirects the AWS SDK to LocalStack** |
| `AWS_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds — required by the SDK, ignored by LocalStack |
| `AWS_REGION` | `eu-central-1` | Same region the init scripts create resources in |
| `TASKS_TABLE` | `tasks` | The DynamoDB table |
| `TASK_EVENTS_QUEUE_URL` | `…/task-events` | Turns on the SQS publisher |
| `NOTIFICATIONS_TOPIC_ARN` | `…:task-notifications` | The topic the Lambda publishes to |

The two containers appear in `docker ps` as **`tasks-api-app-1`** and
**`tasks-api-localstack-1`** — those exact names matter for the `docker exec` and
`docker logs` commands later.

---

## 4. Step 3 — Interact with every API endpoint

Full CRUD, one endpoint at a time. First pin the base URL so the rest is
copy-paste:

```bash
BASE=http://localhost:8080
```

Here are the six endpoints you'll exercise:

| Method | Path | Purpose | Success |
|---|---|---|---|
| `GET` | `/health` | liveness + version | `200` |
| `GET` | `/tasks` | list all tasks | `200` |
| `POST` | `/tasks` | create a task | `201` |
| `GET` | `/tasks/{id}` | fetch one task | `200` |
| `PUT` | `/tasks/{id}` | update (full or partial) | `200` |
| `DELETE` | `/tasks/{id}` | delete a task | `204` |

### Reading the curl flags

Every command below is plain `curl`. These are the only flags used — skim this
once and the rest of the lab reads itself:

| Flag | Long form | What it does | Why it's here |
|---|---|---|---|
| `-s` | `--silent` | Hide curl's progress meter; print only the response body | Clean output; pipes straight into `jq` |
| `-i` | `--include` | Print the response **headers** too | When the header *is* the point: `201`, `Location`, `Allow`, `204` |
| `-X <method>` | `--request` | Set the HTTP method | Needed for `PUT`/`DELETE`; explicit on `POST` for clarity |
| `-H <header>` | `--header` | Add a request header | `Content-Type: application/json` — without it the API returns `415` |
| `-d <data>` | `--data` | Send a request body (the JSON payload) | Carries the task fields on create/update |
| `-o <file>` | `--output` | Write the body to a file instead of the screen | `-o /dev/null` **discards** the body when only the status matters |
| `-w <format>` | `--write-out` | Print chosen values *after* the transfer | `-w '%{http_code}\n'` prints just the HTTP status code |

Two `curl` defaults worth knowing, because they explain why the flags pair up the
way they do:

- Passing `-d` makes curl **default to `POST`**, so `-X POST` on a create is
  technically redundant — it's kept for readability. `PUT`/`DELETE` genuinely need
  `-X`.
- Passing `-d` also defaults the `Content-Type` to
  `application/x-www-form-urlencoded`. That's exactly why every write **must** add
  `-H 'Content-Type: application/json'` to override it — or the API answers `415`.

Not a curl flag, but it appears alongside: **`| jq`** pipes the JSON into `jq`,
which pretty-prints and colours it (install with `brew install jq`, or just drop
it — the raw JSON still prints).

### 4.1 `GET /health` — liveness

```bash
curl -s "$BASE/health" | jq
# { "status": "UP", "version": "local" }
```

### 4.2 `POST /tasks` — create (and capture the id)

`-i` shows the response **headers** so you can see the `201 Created` status and
the `Location` header pointing at the new resource:

```bash
curl -i -X POST "$BASE/tasks" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Learn Docker","description":"for the lab"}'
# HTTP/1.1 201 Created
# Location: /tasks/2f1c…
# {"id":"2f1c…","title":"Learn Docker","description":"for the lab","done":false,"createdAt":"2026-…Z"}
```

Now create one more and **capture its id** into a shell variable so every command
below just works — no copy-pasting ids:

```bash
ID=$(curl -s -X POST "$BASE/tasks" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Write Terraform"}' | jq -r '.id')

echo "New task id: $ID"
```

> The server always controls `id`, `createdAt`, and the initial `done` — if you
> send those fields, they're ignored.

Optional: seed a few more at once with the helper script:

```bash
scripts/local-seed.sh          # creates "Learn Docker", "Write Terraform", "Ship to AWS"
```

### 4.3 `GET /tasks` — list all

```bash
curl -s "$BASE/tasks" | jq
# { "tasks": [ … ], "count": 3 }
```

The response wraps the array in an object with a `count` — handy for clients and
for spotting an empty list at a glance.

### 4.4 `GET /tasks/{id}` — fetch one

```bash
curl -s "$BASE/tasks/$ID" | jq
# { "id": "…", "title": "Write Terraform", "done": false, "createdAt": "…" }
```

### 4.5 `PUT /tasks/{id}` — update (partial *or* full)

`PUT` here is a **partial** update: send only the fields you want to change. Flip
just the `done` flag:

```bash
curl -s -X PUT "$BASE/tasks/$ID" \
  -H 'Content-Type: application/json' \
  -d '{"done":true}' | jq
# { …, "done": true }
```

Or change several fields at once — anything you omit is left untouched:

```bash
curl -s -X PUT "$BASE/tasks/$ID" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Write Terraform (done)","done":true}' | jq
```

### 4.6 `DELETE /tasks/{id}` — remove

`204 No Content` means success with an empty body (`-i` shows the status since
there's nothing to print):

```bash
curl -i -X DELETE "$BASE/tasks/$ID"
# HTTP/1.1 204 No Content

curl -s -o /dev/null -w '%{http_code}\n' "$BASE/tasks/$ID"
# 404   (it's gone)
```

### 4.7 Errors — the API fails *predictably*

Every error uses the same shape `{"error":"<CODE>","message":"…"}` with the
correct HTTP status. Try each:

```bash
# 400 — title is required
curl -s -X POST "$BASE/tasks" -H 'Content-Type: application/json' -d '{}' | jq

# 415 — body must be declared application/json
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/tasks" \
  -H 'Content-Type: text/plain' -d 'hi'
# 415

# 405 — wrong method on a route (note the Allow header)
curl -i -X POST "$BASE/tasks/anything"
# HTTP/1.1 405 Method Not Allowed
# Allow: GET, PUT, DELETE

# 404 — unknown route
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/nope"
# 404
```

**What Step 3 proves:** the full CRUD contract works end-to-end against the
containerized app, and the error handling is consistent — the same behavior a real
client (or your CD smoke test) depends on.

---

## 5. Step 4 — Prove it's *really* (fake) AWS

The curl calls above could, in principle, be hitting an in-memory map. They're
not — here's the proof.

> **New commands ahead.** From here on we stop using `curl` and start driving AWS
> itself. These lines look dense, but they're all built from the same seven
> pieces — learn them once and every command below decodes on sight.

### The building blocks (read this once)

| Piece | What it means |
|---|---|
| `docker exec <container> <cmd>` | Run `<cmd>` **inside a container that's already running** (unlike `docker run`, which starts a *new* one). Ours is `tasks-api-localstack-1` — the name pattern is `<project>-<service>-<index>`. |
| `awslocal` | The **AWS CLI, pre-aimed at LocalStack** (endpoint `http://localhost:4566` + dummy credentials baked in). Anywhere you'd type `aws` against real AWS, you type `awslocal` here — identical subcommands and flags. |
| `bash -lc '<script>'` | Start a shell inside the container to run a **multi-line script that uses variables**. `-c` = "run this string"; `-l` = login shell. Needed whenever one command's output feeds the next. |
| `$(<command>)` | **Command substitution** — run `<command>` and drop its output right there. We use it to stash an ARN or URL into a variable: `T=$( … )`. |
| `--region eu-central-1` | Every AWS call is **region-scoped**, and it must match the region the resource was created in (the init scripts use `eu-central-1`) — otherwise the CLI looks in the wrong place and finds nothing. |
| `--query '<expr>'` | **Reshape/filter the JSON** the CLI got back, *before* printing. The expression is [JMESPath](https://jmespath.org) — a query language for JSON, like `jq` but built into the AWS CLI. |
| `--output text \| table \| json` | How to print the result: **`text`** = bare values (ideal for capturing into a variable), **`table`** = an ASCII grid for humans, **`json`** = the default structured form. |

With that vocabulary, here are the four commands, decoded.

### 5.1 Read straight from DynamoDB

Run the AWS CLI **inside** the LocalStack container (`awslocal` = AWS CLI aimed at
LocalStack):

```bash
docker exec tasks-api-localstack-1 \
  awslocal dynamodb scan --table-name tasks --region eu-central-1 \
  --query "Items[].{id:id.S,title:title.S,done:done.BOOL}" --output table
```

Decoding the parts specific to this command (the shared pieces are in the table
above):

| Part | What it does |
|---|---|
| `dynamodb scan` | The **service** (`dynamodb`) and **operation** (`scan` = read *every* item in the table). |
| `--table-name tasks` | Which table to read — the one the init script created. |
| `--query "Items[].{id:id.S, …}"` | Reshape each item. `Items[]` walks the list of returned items; `{id:…,title:…,done:…}` builds a tidy projection with just those three fields. |
| `.S` / `.BOOL` | DynamoDB stores every value **tagged with its type** — a raw scan returns `{"id":{"S":"abc"},"done":{"BOOL":false}}`. So `id.S` means "the **S**tring value of `id`" and `done.BOOL` the **BOOL**ean; that's why the query digs one level in. |
| `--output table` | Print an ASCII grid instead of JSON — easy to eyeball. |

You'll see the tasks you created, sitting in a real DynamoDB table. Same command,
same output shape you'd get against AWS — only the endpoint differs.

### 5.2 See the event pipeline fire: SQS → Lambda → SNS

Creating a task publishes a `TASK_CREATED` message to **SQS**, which triggers the
**Lambda**, which publishes a notification to **SNS**. SNS has no inbox of its own,
so to *see* the notification we subscribe a throwaway queue to the topic **once**:

```bash
docker exec tasks-api-localstack-1 bash -lc '
R=eu-central-1
T=$(awslocal sns create-topic --name task-notifications --region $R --query TopicArn --output text)
Q=$(awslocal sqs create-queue --queue-name demo-inbox --region $R --query QueueUrl --output text)
A=$(awslocal sqs get-queue-attributes --queue-url "$Q" --attribute-names QueueArn --region $R --query Attributes.QueueArn --output text)
awslocal sns subscribe --topic-arn "$T" --protocol sqs --notification-endpoint "$A" --attributes RawMessageDelivery=true --region $R'
```

Line by line — it creates a throwaway "inbox" queue and wires it to the topic:

| Line | What it does |
|---|---|
| `R=eu-central-1` | Stash the region in a variable so we don't repeat it on every line. |
| `T=$(awslocal sns create-topic … --query TopicArn --output text)` | Create the notifications topic (or, if it exists, just re-fetch it — `create-topic` is **idempotent**) and capture its **ARN** into `T`. |
| `Q=$(awslocal sqs create-queue … --query QueueUrl --output text)` | Create the throwaway `demo-inbox` queue and capture its **URL** into `Q`. |
| `A=$(awslocal sqs get-queue-attributes --queue-url "$Q" --attribute-names QueueArn …)` | Look up that same queue's **ARN** into `A`. |
| `awslocal sns subscribe --topic-arn "$T" --protocol sqs --notification-endpoint "$A"` | The payoff: tell the **topic** to deliver every message it receives to the **queue**. |
| `--attributes RawMessageDelivery=true` | Deliver the **raw** message body, *without* SNS wrapping it in its own JSON envelope — so when we read the queue we see the plain notification text, not metadata around it. |

> **URL vs ARN — why both appear.** A queue has a **URL** (the address you *send to*
> and *receive from*) *and* an **ARN** (`arn:aws:sqs:…` — its globally unique
> *identity*, used to *wire* and *permission* it). `subscribe` connects two
> resources, so it needs the ARN — which is why we fetch `A` separately from `Q`.

Now create a task, then read what the Lambda produced (give it a few seconds):

```bash
curl -s -X POST "$BASE/tasks" \
  -H 'Content-Type: application/json' -d '{"title":"Notify me!"}' >/dev/null

docker exec tasks-api-localstack-1 \
  awslocal sqs receive-message \
  --queue-url http://localhost:4566/000000000000/demo-inbox \
  --region eu-central-1 --query "Messages[].Body" --output text
# -> A new task was created: "Notify me!" (id …)
```

| Part | What it does |
|---|---|
| `sqs receive-message` | **Poll the queue** for waiting messages — the same thing the Lambda's trigger does under the hood. |
| `--queue-url http://localhost:4566/000000000000/demo-inbox` | The inbox to read. `000000000000` is **LocalStack's fake AWS account id** (real AWS uses your 12-digit number); `localhost:4566` works because the command runs *inside* the LocalStack container. |
| `--query "Messages[].Body"` | Pull out just the message **body** — the human-readable line the Lambda published. |

**What this proves — and the key idea:** the `POST` returned `201`
*immediately*; it did **not** wait for the notification. SQS **decouples** the two,
so a slow or failing notifier can never slow down or break task creation.
Publishing the event is best-effort — if SQS were down, the task is still created
and a warning is logged.

> `demo-inbox` is created at runtime (not in code or Terraform), so it disappears
> on teardown. Re-run the subscribe block if you bring the stack up again.

<details>
<summary><b>Optional extras</b> — the safety net and the built-in observability</summary>

**Dead-letter queue (DLQ).** After 3 failed processing attempts a message is
parked in a DLQ instead of looping forever. In a healthy run **both** the main
queue and the DLQ are empty — that pair together proves the Lambda consumed the
message cleanly:

```bash
docker exec tasks-api-localstack-1 bash -lc '
for q in task-events task-events-dlq; do
  URL=$(awslocal sqs get-queue-url --queue-name $q --region eu-central-1 --query QueueUrl --output text)
  echo -n "$q -> "; awslocal sqs get-queue-attributes --queue-url "$URL" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --region eu-central-1 --query Attributes --output json
done'
```

| Part | What it does |
|---|---|
| `for q in task-events task-events-dlq; do … done` | Loop over the two queue names in turn. |
| `get-queue-url --queue-name $q` | Resolve each queue's URL from its name (we only know the names). |
| `echo -n "$q -> "` | Print the queue name as a label; `-n` = no trailing newline, so the numbers land on the same line. |
| `--attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible` | Two counters: **…Messages** = waiting/visible; **…NotVisible** = *in flight* (received, not yet deleted). **Both `0` means the Lambda drained the queue cleanly.** ("Approximate" because SQS is distributed — the count is eventually consistent.) |

**Structured logs & metrics.** The app writes one JSON log line per request and
emits CloudWatch **EMF** metric lines (a single line that is *both* a log entry and
a metric). Read them straight off the container:

```bash
docker logs tasks-api-app-1 | grep '"path":"/tasks"' | tail -5    # request logs
docker logs tasks-api-app-1 | grep TasksCreated | tail -3          # EMF metric lines
```

| Part | What it does |
|---|---|
| `docker logs <container>` | Dump everything the container has printed to `stdout`/`stderr` since it started. |
| `\| grep '<pattern>'` | Keep only the lines that match `<pattern>` (a plain-text or regex filter). |
| `\| tail -5` | Of those, show just the **last 5** lines. |

> CloudWatch Logs, the CloudWatch **alarm**, and **X-Ray** tracing need *real* AWS
> — LocalStack here only runs `dynamodb, sqs, sns, s3, lambda`. Those light up only
> in a real deploy; see [demo.md → Part 2](demo.md#part-2--the-same-demo-against-the-live-aws-stack).

</details>

---

## 6. Step 5 — Tear it down

Back in the terminal running `make compose-up`, press **Ctrl-C**, then:

```bash
make compose-down     # stops both containers and removes their volumes (-v)
```

Under the hood this runs `docker compose -f docker/docker-compose.yml down -v`:

| Part | What it does |
|---|---|
| `docker compose down` | Stop **and remove** both containers plus the network Compose created to link them. |
| `-v` | Also delete the **volumes** — the disk where LocalStack kept your table, queues, and topic. |

The `-v` is what wipes LocalStack's data, so every task you created is gone and the
next `compose-up` starts from a clean slate. Drop the `-v` (run plain
`docker compose … down`) if you ever want your data to survive a restart.

---

## Command cheat-sheet

```bash
# --- run & lifecycle ---
make test            # unit tests
make run             # app only, in-memory, :8080
make compose-up      # full stack on Docker Desktop + LocalStack
make compose-down    # stop + wipe

# --- the API (BASE=http://localhost:8080) ---
curl -s  "$BASE/health"                                             # GET  liveness
curl -s  "$BASE/tasks" | jq                                          # GET  list
ID=$(curl -s -X POST "$BASE/tasks" -H 'Content-Type: application/json' \
       -d '{"title":"x"}' | jq -r '.id')                            # POST create + capture id
curl -s  "$BASE/tasks/$ID" | jq                                      # GET  one
curl -s -X PUT "$BASE/tasks/$ID" -H 'Content-Type: application/json' \
       -d '{"done":true}' | jq                                       # PUT  update
curl -i -X DELETE "$BASE/tasks/$ID"                                  # DELETE

# --- inspect the fake AWS ---
docker exec tasks-api-localstack-1 awslocal dynamodb scan --table-name tasks --region eu-central-1
docker logs tasks-api-app-1 | tail -20
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `compose-up` logs `lambda jar not found … skipping Lambda deploy` | Maven didn't build the jar. Run `mvn -pl lambda -am package`, then `make compose-up` again. |
| `curl: (7) Failed to connect … 8080` | The app isn't up yet (first build is slow), or Step 1's `make run` is still holding the port — Ctrl-C it. |
| First `compose-up` takes minutes | Expected on Apple Silicon — the `linux/amd64` image builds under emulation. Reruns are cached. |
| `docker exec … tasks-api-localstack-1` says *no such container* | The stack isn't running (`docker ps` to check), or your compose project name differs. |
| Event chain / `demo-inbox` returns nothing | Give it a few seconds (it's async); confirm you saw `[init] lambda deploy done` at startup. |
| `jq: command not found` | Install it (`brew install jq`) or drop the `| jq` — the raw JSON still prints. |

---

Next: run the **same** API against real AWS and watch CloudWatch/X-Ray light up —
[demo.md → Part 2](demo.md#part-2--the-same-demo-against-the-live-aws-stack). The
architecture is in [architecture.md](architecture.md); operations in
[runbook.md](runbook.md).
