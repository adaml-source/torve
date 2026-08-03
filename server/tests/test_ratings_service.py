from app.ratings_service import (
    RatingIdentity,
    parse_mdblist_payload,
    parse_omdb_payload,
)


def test_parse_mdblist_payload_extracts_imdb_first_class_fields():
    parsed = parse_mdblist_payload(
        RatingIdentity(media_type="movie", tmdb_id=550),
        {
            "imdbid": "tt0137523",
            "ratings": [
                {"source": "imdb", "value": 8.8, "votes": 2400000},
                {"source": "tmdb", "value": 8.4},
                {"source": "tomatoes", "value": 80},
                {"source": "mdblist", "score": 86},
            ],
        },
    )

    assert parsed is not None
    assert parsed["tmdb_id"] == 550
    assert parsed["imdb_id"] == "tt0137523"
    assert parsed["imdb_score"] == 8.8
    assert parsed["imdb_votes"] == 2400000
    assert parsed["tmdb_score"] == 8.4
    assert parsed["rotten_tomatoes_score"] == 80
    assert parsed["mdblist_score"] == 86.0


def test_parse_mdblist_payload_rejects_tmdb_only_response():
    parsed = parse_mdblist_payload(
        RatingIdentity(media_type="tv", tmdb_id=42),
        {"ratings": [{"source": "tmdb", "value": 7.5}]},
    )
    assert parsed is None


def test_parse_omdb_payload_extracts_imdb_votes_and_secondary_scores():
    parsed = parse_omdb_payload(
        RatingIdentity(media_type="movie", tmdb_id=550, imdb_id="tt0137523"),
        {
            "Response": "True",
            "imdbID": "tt0137523",
            "imdbRating": "8.8",
            "imdbVotes": "2,400,000",
            "Metascore": "67",
            "Ratings": [{"Source": "Rotten Tomatoes", "Value": "80%"}],
        },
    )

    assert parsed is not None
    assert parsed["imdb_score"] == 8.8
    assert parsed["imdb_votes"] == 2400000
    assert parsed["metacritic_score"] == 67
    assert parsed["rotten_tomatoes_score"] == 80
