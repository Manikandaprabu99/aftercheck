# Automated checks

The PGN parser unit test uses two small illustrative PGN records to verify extraction of player names, result, ECO code and game URLs. These are parser fixtures only; they are not asserted to be actual games played by a user.

The GitHub Actions job is the authoritative build check. A code commit is not considered build-verified until the workflow has completed successfully.
