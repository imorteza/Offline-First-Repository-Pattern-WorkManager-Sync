<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

$storagePath = __DIR__ . '/data.json';

function read_storage(string $path): array
{
    if (!file_exists($path)) {
        file_put_contents($path, json_encode([]));
    }

    $raw = file_get_contents($path);
    $data = json_decode($raw, true);

    return is_array($data) ? $data : [];
}

function write_storage(string $path, array $data): void
{
    file_put_contents($path, json_encode($data, JSON_PRETTY_PRINT));
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    $items = read_storage($storagePath);
    echo json_encode(['items' => $items]);
    exit;
}

if ($method === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!is_array($input)) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        exit;
    }

    $items = read_storage($storagePath);
    $existingById = [];

    foreach ($items as $item) {
        if (isset($item['id'])) {
            $existingById[(string) $item['id']] = $item;
        }
    }

    if (isset($input['items']) && is_array($input['items'])) {
        $incoming = $input['items'];
    } elseif (isset($input['text'])) {
        $incoming = [$input];
    } else {
        http_response_code(400);
        echo json_encode(['error' => 'Missing payload']);
        exit;
    }

    $saved = [];
    foreach ($incoming as $rawItem) {
        if (!is_array($rawItem) || !isset($rawItem['text'])) {
            continue;
        }

        $id = isset($rawItem['id']) && $rawItem['id'] !== ''
            ? (string) $rawItem['id']
            : uniqid('srv_', true);

        $record = [
            'id' => $id,
            'text' => (string) $rawItem['text'],
            'createdAt' => isset($rawItem['createdAt']) ? (int) $rawItem['createdAt'] : time(),
        ];

        $existingById[$id] = $record;
        $saved[] = $record;
    }

    $items = array_values($existingById);
    write_storage($storagePath, $items);

    echo json_encode(['items' => $saved]);
    exit;
}

http_response_code(405);
echo json_encode(['error' => 'Method not allowed']);
