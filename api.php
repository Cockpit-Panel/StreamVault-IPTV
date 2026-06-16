<?php
header('Content-Type: application/json');

$response = [
    "portals" => [
        [
            "type" => "xc",
            "id" => 1,
            "name" => "Demo XC Portal",
            "url" => "http://pradahype.com"
        ],
        [
            "type" => "stalker",
            "id" => 2,
            "name" => "Demo Stalker Portal",
            "url" => "https://easy14us.com//c/"
        ]
    ]
];

echo json_encode($response, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
