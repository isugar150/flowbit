-- The initial consonants of Korean syllables, for searching titles by them: '결제 개편' -> 'ㄱㅈ ㄱㅍ'
-- (docs/features/search.md, D-133). Anything that is not a Hangul syllable is kept as it is.
CREATE FUNCTION hangul_initials(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT coalesce(string_agg(
        CASE
            WHEN ascii(ch) BETWEEN 44032 AND 55203
                THEN substr('ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ', (ascii(ch) - 44032) / 588 + 1, 1)
            ELSE ch
        END, '' ORDER BY position), '')
    FROM unnest(string_to_array(input, NULL)) WITH ORDINALITY AS chars(ch, position)
$$;
